# LogBus Architecture

LogBus is a small logging library for Kotlin Multiplatform (Android + iOS). No UI — other apps
import it. This doc is the plan: what we build now, and why.

The name says it: **LogBus is a bus.** The bus is the product. It takes a log and hands it to every
route. Routes (where logs actually go — console, file, etc.) are *not* part of this work; people add
their own. We focus on making the bus clean and correct, so we don't have to rebuild it later.

---

## What we build now

An **instance-based** bus. You create one, give it routes, and use it:

```kotlin
val bus = LogBus(routes = listOf(myRoute))
bus.d("hello")
```

No global/singleton for now. (An instance can always get a global shortcut added later; the reverse
is a rewrite — so instance-first keeps us safe.)

---

## The pieces

### `LogEvent` — one log, as an object
```kotlin
public data class LogEvent(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val error: Throwable? = null,
    val timeMillis: Long,
    val metadata: Map<String, Any?> = emptyMap(),
)
```
Everything downstream works on this one object. **Why an object, not loose values:** if we add a
field later, no route breaks. `metadata` is spare key–value space — mainly for interceptors to attach
context (like a user id) without touching the message text.

### `LogLevel` — open, so devs can add their own
```kotlin
public interface LogLevel {
    val name: String
    val priority: Int
    val emoji: String?
}

public enum class DefaultLevel(override val priority: Int, override val emoji: String?) : LogLevel {
    VERBOSE(100, "🔍"), DEBUG(200, "🐞"), INFO(300, "ℹ️"), WARNING(400, "⚠️"), ERROR(500, "❌");
}
```
Ships 5 defaults; devs implement `LogLevel` for their own (e.g. `Analytics` with priority 350).
**Why an interface:** custom levels are a real want. **Why `priority` is a number:** it gives a clear
order without depending on how the enum happens to be written. **Why `emoji` is on the level:** each
level carries its own icon — but the **formatter** decides whether to actually show it.

### `LogRoute` — the one thing you implement
`LogRoute` is a **sealed family** with two members. You don't extend it directly — you pick the one
that matches what your destination does and override its `emit`:

```kotlin
public sealed class LogRoute(
    protected val formatter: LogFormatter? = null,
) {
    // Override to filter; defaults to accepting everything.
    public open fun isLoggable(event: LogEvent): Boolean = true

    // Shared format step, run by each member's log() before emit.
    protected fun format(event: LogEvent): LogEvent =
        if (formatter != null) event.copy(message = formatter.format(event)) else event
}

// Fast, non-suspending work (console, a crash line). Runs INLINE on the caller's thread.
public abstract class SyncLogRoute(formatter: LogFormatter? = null) : LogRoute(formatter) {
    public fun log(event: LogEvent) {              // non-suspend
        if (!isLoggable(event)) return
        emit(format(event))
    }
    protected abstract fun emit(event: LogEvent)   // non-suspend — the ONE method you write
}

// Suspending I/O (file, network). Runs on the bus's background worker.
public abstract class AsyncLogRoute(formatter: LogFormatter? = null) : LogRoute(formatter) {
    public suspend fun log(event: LogEvent) {              // suspend
        if (!isLoggable(event)) return
        emit(format(event))
    }
    protected abstract suspend fun emit(event: LogEvent)   // suspend — the ONE method you write
}
```
You write **one** method — `emit`. Everything before it is the route's own little pipeline, run for
you inside `log()` in a fixed order: **`isLoggable` filter → optional `formatter` → `emit`**.

**Why two types instead of one:** "synchronous" and "asynchronous" genuinely differ in Kotlin because
`suspend` is part of a function's signature — and you can't call a `suspend emit` from a non-suspend,
inline call path (there's no cross-platform `runBlocking` in KMP). So a route that must write *before
the call returns* (console, crash logging) needs a **non-suspend** `emit`, while a route doing
suspending I/O needs a `suspend` one. That difference can't be hidden behind a single interface, so it
becomes two types. Everything they share — `formatter`, `isLoggable`, the `format` step — lives once on
the sealed base; only the `log`/`emit` pair differs.

**Why `sealed`:** it closes the family. `SyncLogRoute` and `AsyncLogRoute` are the only two members, so
the bus can dispatch on them exhaustively (async → worker, sync → inline) with no `else` branch, and a
user can't add a third kind the bus wouldn't know how to deliver. Users extend the two concrete
`abstract` types freely; the bare base stays off-limits.

**Why `emit` is `protected`:** the only way in is `log()`, so the filter and formatter can never be
skipped. **Why it works standalone:** `log()` runs the whole pipeline itself, so a route needs no bus
— just call `route.log(event)`. **Why a single `LogEvent` reaches `emit` (not event + string):** if a
formatter is set we fold its output back into `message`, so `emit` always gets one already-formatted
event (with `level`, `tag`, `error` still there for routing decisions).

**Pick sync only for fast work:** a `SyncLogRoute` runs on the caller's thread with no mailbox, no
per-route ordering buffer, and no isolation — a slow or blocking sync `emit` slows the caller. For
anything that can block or suspend, use `AsyncLogRoute`.

### `LogFormatter` — a route's tool for making text
```kotlin
public fun interface LogFormatter {
    fun format(event: LogEvent): String
}
```
A formatter turns an event into a string, **any way the dev wants** — plain, JSON, boxed, one-line.
**It belongs to the route, not the bus:** you pass a formatter to a `LogRoute` and its pipeline applies
it for you (folding the result into `message`) before `emit`. Different routes can use different
formatters, and a route with no formatter just passes the raw event through. The bus never touches
formatting.

We only define the interface now. Concrete formatters (like a box-and-emoji one) are just add-ons we
can ship later.

### `LogInterceptor` — change or drop a log
```kotlin
public fun interface LogInterceptor {
    fun intercept(event: LogEvent): LogEvent?   // return a changed event, or null to drop it
}
```
**Central interceptors run once, before the log reaches any route.** Good for adding context to every
log, hiding secrets, or dropping some logs. You pass them when you build the bus.
Route-level interceptors are planned for later (same type, applied per route).

### `LogBus` — the bus
```kotlin
public class LogBus(
    private val routes: List<LogRoute>,
    private val interceptors: List<LogInterceptor> = emptyList(),
) {
    fun log(level: LogLevel, message: String, tag: String? = null, error: Throwable? = null)

    fun v(message: String, tag: String? = null) = log(DefaultLevel.VERBOSE, message, tag)
    fun d(message: String, tag: String? = null) = log(DefaultLevel.DEBUG, message, tag)
    fun i(message: String, tag: String? = null) = log(DefaultLevel.INFO, message, tag)
    fun w(message: String, tag: String? = null) = log(DefaultLevel.WARNING, message, tag)
    fun e(message: String, tag: String? = null, error: Throwable? = null) = log(DefaultLevel.ERROR, message, tag, error)
}
```
`log(...)` takes any level, so custom levels work: `bus.log(Analytics, "checkout done")`.
The `v/d/i/w/e` methods are just shortcuts for the 5 defaults.

---

## How a log flows

```
bus.d("hi")
   └─► build LogEvent (adds default tag "LogBus" if none, timeMillis, empty metadata)
        └─► run central interceptors in order   ── any returns null? ─► drop, stop here
             └─► hand the event to each route (see Threading)
                  └─► route.log(event) runs the route pipeline:
                       isLoggable filter ── false? ─► skip this route
                        └─► optional formatter (folded into message)
                             └─► emit(event) — the route sends it
```

---

## Threading

A route's delivery model depends on its type. `deliver` handles both — async routes through workers,
sync routes inline:

```kotlin
private fun deliver(event: LogEvent) {
    workers.forEach { it.send(event) }   // async: instant enqueue, never blocks
    for (route in syncRoutes) {          // sync: inline on the caller's thread
        try { route.log(event) } catch (_: Throwable) { /* one bad route can't stop the rest */ }
    }
}
```

### Async routes — one worker each

For an `AsyncLogRoute`, the app thread never waits, logs still come out **in order**, and one slow
route can't hold up the others. We get this with **one worker per async route**.

Each async route gets its own **mailbox** (a `Channel<LogEvent>`) and a single **worker coroutine**
that drains it. `deliver` just drops the event into each mailbox and returns. Inside each worker (see
`RouteWorker`), one coroutine pulls events out one at a time:

```kotlin
for (event in mailbox) {
    try { route.log(event) } catch (_: Throwable) { /* keep draining */ }
}
```

**Why this keeps order:** a channel is a FIFO queue, and a *single* worker takes events out one at a
time, finishing `route.log(e1)` before it looks at `e2`. Same order in, same order out.

**Why routes stay isolated:** each route drains its own mailbox, so a slow network route just fills
its own queue — the other routes keep flowing.

**Where the worker lives:** in the bus, not the route. The mailbox+worker is the bus's **async delivery
layer** — LogBus's version of Logback's `AsyncAppender`. The bus owns it because the bus owns the
`CoroutineScope`.

**Overflow:** the mailbox is bounded (default 1024) and **drops the oldest** event if a route stalls
under a flood — bounded memory, never blocks the app, keeps the most recent logs. Under normal load
nothing is ever dropped.

Background work uses the shared thread pool (`Dispatchers.Default`), not one thread per route — a
parked worker holds no thread, so this stays cheap.

### Sync routes — inline, no worker

A `SyncLogRoute` has no mailbox and no worker. `deliver` calls its `log()` **inline on the calling
thread**, so the write finishes before `bus.log(...)` returns. That's the point: console output stays
immediate, and a crash line is written before the process can die — an async worker might not drain in
time.

The cost is the mirror image of the async guarantees: a sync route runs on the caller's thread, so a
slow `emit` slows the caller, and there's no per-route ordering buffer. Each sync `log()` gets its own
`try/catch` in `deliver`, so a broken sync route still can't crash the caller or stop the other routes
— but keep sync `emit`s fast. For anything that can block or suspend, use an `AsyncLogRoute`.

**Stopping:** `bus.close()` first flips a `closed` flag so no new logs are accepted (this gates the
inline sync path, since sync routes don't go through a channel that could be closed), then closes every
async mailbox, lets the workers finish what's already queued, and ends them. Call it on shutdown, and
in tests to wait for all logs before asserting.

---

## Rules the bus follows

- **Never crash the app.** Bad input, logging before setup, anything — the bus stays quiet and safe.
- **No routes → do nothing.** No event built, no work.
- **A broken route can't stop the others.** Its error is caught; the rest still get the log.

---

## Cross-platform layout

```
logbus/src/
  commonMain/   ← everything: LogBus, LogEvent, LogLevel, DefaultLevel, LogRoute,
                  LogFormatter, LogInterceptor
  androidMain/  ← nowMillis() → System.currentTimeMillis()
  iosMain/      ← nowMillis() → platform clock
```

The only platform-specific bit the bus needs is the clock:

```kotlin
internal expect fun nowMillis(): Long
```

Everything else is plain Kotlin in `commonMain`. `explicitApi()` is on, so public things say `public`.

---

## Later (not now, but the design leaves room)

- Global/singleton shortcut over an instance.
- Route-level interceptors — slot into the route pipeline, before the formatter (the `log()` step is
  already the single place to add them).
- Batch mode: a route can group several logs into one `emit` (e.g. `n / batchSize` network calls).
  Layers on top of the worker without changing the bus.
- `onError` callback so route failures are visible.
- Ready-made formatters (e.g. box + emoji).
- Helpers like `withTag` and Compose support — only if wanted, likely as separate add-ons.
```