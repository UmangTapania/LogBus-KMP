package io.github.umangtapania.logbus.routes

import io.github.umangtapania.logbus.LogEvent
import io.github.umangtapania.logbus.LogFormatter
import io.github.umangtapania.logbus.SyncLogRoute

/**
 * Prints logs to the platform's native console — Logcat on Android, `NSLog` on iOS (see
 * [writeToConsole]). A [SyncLogRoute] because console output should be **immediate**: the line is
 * written before the log call returns, so it shows up in order and a crash line lands before the
 * process can die.
 *
 * The level and tag are handed to the platform logger, which shows them the native way — real Logcat
 * levels and tag on Android, folded into the line on iOS. The body it prints is:
 * - the event's `message` — already shaped by the [formatter] if one was given (the base pipeline folds
 *   the formatter's output into `message` before `emit`), otherwise the raw message; plus
 * - the error's stack trace on the next line, when there is one.
 *
 * ```kotlin
 * val bus = LogBus(routes = listOf(ConsoleRoute()))
 * bus.d("hello", tag = "Startup")   // Logcat: DEBUG/Startup: hello
 * ```
 *
 * It's a `SyncLogRoute`, so the write runs on the calling thread — fine for console, which is fast.
 */
public class ConsoleRoute(
    formatter: LogFormatter? = null,
) : SyncLogRoute(formatter) {

    override fun emit(event: LogEvent) {
        // message is already formatted if a formatter was set; append the error trace so it's never lost.
        val text = event.error
            ?.let { "${event.message}\n${it.stackTraceToString()}" }
            ?: event.message
        writeToConsole(event.level, event.tag, text)
    }
}