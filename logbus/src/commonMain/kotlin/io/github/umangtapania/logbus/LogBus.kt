package io.github.umangtapania.logbus

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private const val DEFAULT_TAG = "LogBus"

/**
 * The bus. You build one with routes (and optional interceptors), then log to it. Each log is built
 * into a [LogEvent], run through the central interceptors, and handed to every route. [AsyncLogRoute]s
 * are delivered on a background worker (the calling thread never waits); [SyncLogRoute]s run inline on
 * the calling thread, so their write finishes before the log call returns.
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

    // Routes split by delivery model, partitioned once up front. Async routes each get their own worker
    // (mailbox + single consumer coroutine); sync routes run inline in deliver(). Both lists are empty
    // when there are no routes, so the "no routes → do nothing" rule still holds.
    private val syncRoutes: List<SyncLogRoute> = routes.filterIsInstance<SyncLogRoute>()
    private val workers: List<RouteWorker> =
        routes.filterIsInstance<AsyncLogRoute>().map { RouteWorker(it, scope) }

    // Flipped by close(). Gates new logs so sync routes (which don't go through a channel) also stop
    // accepting after close — the async workers already ignore sends once their mailbox is closed.
    @Volatile
    private var closed = false

    /** Log at any level — including custom ones: `bus.log(Analytics, "checkout done")`. */
    public fun log(level: LogLevel, message: String, tag: String? = null, error: Throwable? = null) {
        if (closed || routes.isEmpty()) return // closed or no routes → do nothing, don't build the event
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
     * Hands the event to each route. Async routes get it via their mailbox — instant, never blocks;
     * each worker drains in order, so logs stay linear per route and a slow route can't hold up the
     * others. Sync routes run inline here on the calling thread, each in its own try/catch so one
     * broken sync route can't crash the caller or stop the remaining routes.
     */
    private fun deliver(event: LogEvent) {
        for (route in syncRoutes) {        // sync: inline on the caller's thread
            try {
                route.log(event)
            } catch (_: Throwable) {
                // Contain a misbehaving sync route: the caller never sees it, and the other routes still run.
            }
        }
        workers.forEach { it.send(event) } // async: instant enqueue, never blocks
    }

    /**
     * Stop the bus. New sends are ignored; whatever is already queued in async workers finishes
     * draining. Sync routes have no worker, so there's nothing to drain for them. Call on app shutdown,
     * and in tests to wait for all logs to be delivered before asserting.
     */
    public suspend fun close() {
        closed = true                  // stop accepting new logs (gates the inline sync path)
        workers.forEach { it.close() } // close every mailbox first (stops intake, lets drains finish)
        workers.forEach { it.join() }  // then wait for every worker to drain and end
    }
}
