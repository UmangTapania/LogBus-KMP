package com.github.umangtapania.logbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val DEFAULT_TAG = "LogBus"

/**
 * The bus. You build one with routes (and optional interceptors), then log to it. Each log is built
 * into a [LogEvent], run through the central interceptors, and handed to every route on a background
 * thread — the calling thread never waits.
 *
 * ```kotlin
 * val bus = LogBus(routes = listOf(myRoute))
 * bus.d("hello")
 * ```
 */
public class LogBus internal constructor(
    private val routes: List<LogRoute>,
    private val interceptors: List<LogInterceptor>,
    private val scope: CoroutineScope,
) {
    /**
     * @param routes where logs go. With no routes the bus does nothing at all.
     * @param interceptors central interceptors, run once in order before any route sees the event.
     */
    public constructor(
        routes: List<LogRoute>,
        interceptors: List<LogInterceptor> = emptyList(),
    ) : this(routes, interceptors, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** Log at any level — including custom ones: `bus.log(Analytics, "checkout done")`. */
    public fun log(level: LogLevel, message: String, tag: String? = null, error: Throwable? = null) {
        if (routes.isEmpty()) return // no routes → do nothing, don't even build the event
        try {
            var event = LogEvent(
                level = level,
                tag = tag ?: DEFAULT_TAG,
                message = message,
                error = error,
                timeMillis = nowMillis(),
            )
            for (interceptor in interceptors) {
                event = interceptor.intercept(event) ?: return // null → drop the log
            }
            deliver(event)
        } catch (_: Throwable) {
            // Never crash the caller. A misbehaving interceptor stays contained here.
        }
    }

    public fun v(message: String, tag: String? = null): Unit = log(DefaultLevel.VERBOSE, message, tag)
    public fun d(message: String, tag: String? = null): Unit = log(DefaultLevel.DEBUG, message, tag)
    public fun i(message: String, tag: String? = null): Unit = log(DefaultLevel.INFO, message, tag)
    public fun w(message: String, tag: String? = null): Unit = log(DefaultLevel.WARNING, message, tag)
    public fun e(message: String, tag: String? = null, error: Throwable? = null): Unit =
        log(DefaultLevel.ERROR, message, tag, error)

    /**
     * The only place threading lives. Starts a background task per route so the app thread never
     * waits, and catches each route's errors so one broken route can't stop the others. Later this
     * becomes "one worker per route" (keeps logs in order) by changing only this method.
     */
    private fun deliver(event: LogEvent) {
        routes.forEach { route ->
            scope.launch {
                try {
                    route.log(event)
                } catch (_: Throwable) {
                    // keep going — the other routes still get the log
                }
            }
        }
    }
}
