package io.github.umangtapania.logbus

/**
 * Change or drop a log before it reaches any route. Return a modified [LogEvent], or `null` to drop
 * the log entirely.
 *
 * Central interceptors (passed when you build the [LogBus]) run once, in order, before delivery —
 * good for adding context to every log, hiding secrets, or filtering. Route-level interceptors are
 * planned for later (same type, applied per route).
 */
public fun interface LogInterceptor {
    public fun intercept(event: LogEvent): LogEvent?
}
