package io.github.umangtapania.logbus

/** The only platform-specific bit the bus needs: the current wall-clock time in milliseconds. */
internal expect fun nowMillis(): Long
