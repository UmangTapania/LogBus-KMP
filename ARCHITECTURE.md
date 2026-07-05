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
```kotlin
public abstract class LogRoute(
    protected val formatter: LogFormatter? = null,
) {
    // Callers use this (bus fans out to it; or call it standalone). Runs the route pipeline.
    public suspend fun log(event: LogEvent) {
        if (!isLoggable(event)) return
        val finished = if (formatter != null) event.copy(message = formatter.format(event)) else event
        emit(finished)
    }

    // Override to filter; defaults to accepting everything.
    public open fun isLoggable(event: LogEvent): Boolean = true

    // The ONE method you write: send the finished event to your destination.
    protected abstract suspend fun emit(event: LogEvent)
}
```
You write **one** method — `emit`. Everything before it is the route's own little pipeline, run for
you inside `log()` in a fixed order: **`isLoggable` filter → optional `formatter` → `emit`**.

**Why an abstract class, not a bare interface:** the pipeline (filter, then format) has to run *before*
your code, so it must live in a method you don't override (`log`), ending in the one you do (`emit`).
**Why `emit` is `protected`:** the only way in is `log()`, so the filter and formatter can never be
skipped. **Why it works standalone:** `log()` runs the whole pipeline itself, so a route needs no bus
— just call `route.log(event)`. **Why a single `LogEvent` reaches `emit` (not event + string):** if a
formatter is set we fold its output back into `message`, so `emit` always gets one already-formatted
event (with `level`, `tag`, `error` still there for routing decisions).

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

Same idea as the original for now: when you log, we start a **new background task for each route**.
The app thread never waits.

This is hidden behind one internal step:

```kotlin
private fun deliver(event: LogEvent) {
    routes.forEach { route ->
        scope.launch {
            try { route.log(event) } catch (t: Throwable) { /* keep going */ }
        }
    }
}
```

**Why hide it:** later we switch to "one worker per route" (keeps logs in order, stops a slow route
from affecting others) by changing *only* this step. Nothing a dev writes will change.

Background work uses the shared thread pool (`Dispatchers.Default`), not one thread per route — so
this is cheap. Note: order between logs is **not** guaranteed yet; that comes with the worker upgrade.

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
- One worker per route (keeps order, isolates slow routes) + a `close()` to stop them.
- A way for tests to wait for logs to finish.
- `onError` callback so route failures are visible.
- Ready-made formatters (e.g. box + emoji).
- Helpers like `withTag` and Compose support — only if wanted, likely as separate add-ons.
```