package io.github.umangtapania.logbus

/**
 * A log level — open, so devs can add their own (e.g. an `Analytics` level with priority 350).
 *
 * [priority] gives a clear order that doesn't depend on how any enum happens to be written.
 * [emoji] is the level's own icon, but the **formatter** decides whether to actually show it.
 */
public interface LogLevel {
    public val name: String
    public val priority: Int
    public val emoji: String?
}

/** The five levels LogBus ships with. Implement [LogLevel] yourself for custom ones. */
public enum class DefaultLevel(
    override val priority: Int,
    override val emoji: String?,
) : LogLevel {
    VERBOSE(100, "🔍"),
    DEBUG(200, "🐞"),
    INFO(300, "ℹ️"),
    WARNING(400, "⚠️"),
    ERROR(500, "❌"),
}
