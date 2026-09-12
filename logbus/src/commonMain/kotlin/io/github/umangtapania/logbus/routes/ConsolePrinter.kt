package io.github.umangtapania.logbus.routes

import io.github.umangtapania.logbus.LogLevel

/**
 * The one platform-specific bit [ConsoleRoute] needs: write a line to the platform's native console.
 * - **Android** → `android.util.Log`, so logs land in Logcat with the right level (errors show as
 *   errors) and tag, and long text is chunked past Logcat's ~4000-char line limit.
 * - **iOS** → `NSLog`, so logs show in the Xcode console and Console.app.
 *
 * Kept `internal` — [ConsoleRoute] is the public door; this is just how it reaches each platform.
 *
 * @param level the log's level, mapped to the platform's own level where one exists (Android).
 * @param tag the log's tag.
 * @param text the finished body to print (message, plus the error's stack trace if there was one).
 */
internal expect fun writeToConsole(level: LogLevel, tag: String, text: String)