package io.github.umangtapania.logbus

/**
 * One log, as an object.
 *
 * Everything downstream (interceptors, routes, formatters) works on this single object. Keeping it
 * an object means adding a field later won't break existing routes. [metadata] is spare key–value
 * space — mainly for interceptors to attach context (like a user id) without touching the message.
 */
public data class LogEvent(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val error: Throwable? = null,
    val timeMillis: Long,
    val metadata: Map<String, Any?> = emptyMap(),
)
