package io.github.umangtapania.logbus.routes

import android.util.Log
import io.github.umangtapania.logbus.LogLevel

// Logcat silently drops anything past ~4000 characters on a single line, so long text is split into
// chunks. (Napier does the same — without it, the tail of a big log just vanishes.)
private const val MAX_LOGCAT_CHUNK = 4000

internal actual fun writeToConsole(level: LogLevel, tag: String, text: String) {
    val priority = level.toLogcatPriority()
    if (text.length <= MAX_LOGCAT_CHUNK) {
        Log.println(priority, tag, text)
        return
    }
    var start = 0
    while (start < text.length) {
        val end = minOf(start + MAX_LOGCAT_CHUNK, text.length)
        Log.println(priority, tag, text.substring(start, end))
        start = end
    }
}

// Map our numeric priority onto Logcat's fixed levels, so an error shows red and level filtering works.
// Ranges (not exact matches) so custom levels land on the nearest Logcat level by proximity.
private fun LogLevel.toLogcatPriority(): Int = when {
    priority < 200 -> Log.VERBOSE
    priority < 300 -> Log.DEBUG
    priority < 400 -> Log.INFO
    priority < 500 -> Log.WARN
    else -> Log.ERROR
}