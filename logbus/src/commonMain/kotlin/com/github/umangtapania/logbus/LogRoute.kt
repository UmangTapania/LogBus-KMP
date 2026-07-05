package com.github.umangtapania.logbus

/**
 * A destination for logs — console, file, network, anywhere. You write **one** thing: [emit], which
 * says what to actually do with a finished log.
 *
 * Everything before that is the route's pipeline, run for you inside [log] in a fixed order:
 * [isLoggable] filter → optional [formatter] → [emit]. Because [log] runs the whole pipeline itself,
 * a route works on its own with no bus — just call `route.log(event)`. The bus is only a fan-out over
 * many routes' `log()`.
 *
 * If a [formatter] is set, the event's `message` is replaced with its output before [emit], so [emit]
 * always gets a single, already-formatted [LogEvent] (with `level`, `tag`, `error` still available).
 */
public abstract class LogRoute(
    protected val formatter: LogFormatter? = null,
) {
    /**
     * The single entry point (bus or standalone). Filters, formats, then calls [emit]. Kept in one
     * place so route-level interceptors can slot in here later without touching individual routes.
     */
    public suspend fun log(event: LogEvent) {
        if (!isLoggable(event)) return
        val finished = if (formatter != null) event.copy(message = formatter.format(event)) else event
        emit(finished)
    }

    /**
     * A per-route filter: return `false` to skip this event before any formatting or emitting.
     * Defaults to accepting everything; override to filter by level, tag, or anything on the event
     * (e.g. `event.level.priority >= DefaultLevel.WARNING.priority`).
     */
    public open fun isLoggable(event: LogEvent): Boolean = true

    /** The one method you override: send the finished (already-formatted) event to your destination. */
    protected abstract suspend fun emit(event: LogEvent)
}