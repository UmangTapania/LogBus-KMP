package io.github.umangtapania.logbus

/**
 * A destination for logs — console, file, network, anywhere. This is the sealed *family*: you don't
 * extend it directly, you extend one of its two members and override **one** thing, [SyncLogRoute.emit]
 * or [AsyncLogRoute.emit], which says what to actually do with a finished log.
 *
 * Pick by what your destination does:
 * - [SyncLogRoute] — fast, non-suspending work (console, a crash line). Runs **inline** on the caller's
 *   thread, so the write finishes before the log call returns. No worker.
 * - [AsyncLogRoute] — suspending I/O (file, network). Runs on the bus's background worker, so the
 *   caller never waits and a slow route can't hold up the others.
 *
 * Everything before `emit` is shared and lives here: the [isLoggable] filter and the optional
 * [formatter] step, applied in a fixed order by each member's `log`: [isLoggable] → [format] → `emit`.
 * Because `log` runs the whole pipeline itself, a route works on its own with no bus — just call
 * `route.log(event)`. The bus is only a fan-out over many routes' `log()`.
 *
 * `sealed` keeps the hierarchy closed: [SyncLogRoute] and [AsyncLogRoute] are the only two members, so
 * the bus can tell them apart exhaustively, and users can't add a third kind the bus wouldn't know how
 * to deliver to.
 */
public sealed class LogRoute(
    protected val formatter: LogFormatter? = null,
) {
    /**
     * A per-route filter: return `false` to skip this event before any formatting or emitting.
     * Defaults to accepting everything; override to filter by level, tag, or anything on the event
     * (e.g. `event.level.priority >= DefaultLevel.WARNING.priority`).
     */
    public open fun isLoggable(event: LogEvent): Boolean = true

    /**
     * The shared format step, run by each member's `log` before `emit`. If a [formatter] is set, the
     * event's `message` is replaced with its output, so `emit` always gets a single, already-formatted
     * [LogEvent] (with `level`, `tag`, `error` still available). No formatter → the event is unchanged.
     */
    protected fun format(event: LogEvent): LogEvent =
        if (formatter != null) event.copy(message = formatter.format(event)) else event
}

/**
 * A synchronous destination. [emit] is a plain function that runs **inline on the caller's thread**, so
 * the write completes before the log call returns — exactly what you want for console output and for
 * crash lines that must be written before the process dies.
 *
 * The tradeoff: it runs on the caller's thread, with no mailbox, no per-route ordering buffer, and no
 * isolation. Keep [emit] **fast and non-blocking** — for slow or suspending I/O, use [AsyncLogRoute].
 */
public abstract class SyncLogRoute(
    formatter: LogFormatter? = null,
) : LogRoute(formatter) {
    /**
     * The single entry point (bus or standalone). Filters, formats, then calls [emit]. Kept in one
     * place so route-level interceptors can slot in here later without touching individual routes.
     */
    public fun log(event: LogEvent) {
        if (!isLoggable(event)) return
        emit(format(event))
    }

    /** The one method you override: send the finished (already-formatted) event to your destination. */
    protected abstract fun emit(event: LogEvent)
}

/**
 * An asynchronous destination. [emit] is a `suspend` function run on the bus's background worker (one
 * mailbox + one consumer coroutine per route), so the caller never waits and a slow route only fills
 * its own mailbox — it can't hold up the other routes. Use this for file, network, or any suspending
 * I/O.
 *
 * Standalone (no bus), `log` is still a suspend function you can call from a coroutine.
 */
public abstract class AsyncLogRoute(
    formatter: LogFormatter? = null,
) : LogRoute(formatter) {
    /**
     * The single entry point (bus or standalone). Filters, formats, then calls [emit]. Kept in one
     * place so route-level interceptors can slot in here later without touching individual routes.
     */
    public suspend fun log(event: LogEvent) {
        if (!isLoggable(event)) return
        emit(format(event))
    }

    /** The one method you override: send the finished (already-formatted) event to your destination. */
    protected abstract suspend fun emit(event: LogEvent)
}
