package io.github.umangtapania.logbus.routes

import io.github.umangtapania.logbus.LogLevel
import platform.Foundation.NSLog

internal actual fun writeToConsole(level: LogLevel, tag: String, text: String) {
    // NSLog has no level/tag concept, so we fold them into the line ourselves (with the level's emoji
    // in front, if it has one). Passing the line as a "%@" argument — never as the format string —
    // keeps a message that contains a '%' from being read as a format specifier.
    val emoji = level.emoji?.let { "$it " } ?: ""
    NSLog("%@", "$emoji${level.name}/$tag: $text")
}