package io.github.umangtapania.logbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Wraps one [AsyncLogRoute] with its own mailbox (a channel) and a single worker coroutine that drains
 * it. Because one coroutine pulls events one at a time and finishes each `route.log()` before taking the
 * next, this route sees logs in the exact order they were sent — and a slow route only fills its own
 * mailbox, it never blocks the other routes.
 *
 * This is the bus's async delivery layer — LogBus's equivalent of Logback's `AsyncAppender`. Only async
 * routes get a worker; [SyncLogRoute]s run inline in the bus and never reach here. The route itself
 * knows nothing about channels or scopes.
 */
internal class RouteWorker(
    private val route: AsyncLogRoute,
    scope: CoroutineScope,
    capacity: Int = DEFAULT_CAPACITY,
) {
    // A bounded FIFO mailbox. On overflow (a route stalls under a flood) we drop the OLDEST events and
    // keep the newest — the ones right before a hang/crash are the useful ones.
    private val mailbox = Channel<LogEvent>(capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // THE single consumer. The for-loop suspends when the mailbox is empty and resumes when an event
    // arrives; it runs route.log() to completion before taking the next event, so delivery stays in
    // order. try/catch keeps one bad log from killing the worker (a broken route can't stop its own
    // future logs, or the other routes).
    private val job: Job = scope.launch {
        for (event in mailbox) {
            try {
                route.log(event)
            } catch (_: Throwable) {
                // keep draining
            }
        }
    }

    // Hand an event to this route's mailbox. Never blocks the caller: with DROP_OLDEST, trySend always
    // succeeds (it evicts the oldest queued event instead of failing or suspending).
    fun send(event: LogEvent) {
        mailbox.trySend(event)
    }

    // Stop taking new logs. Closing the channel makes the for-loop finish its remaining queued events,
    // then end — so nothing already queued is lost.
    fun close() {
        mailbox.close()
    }

    // Wait for the worker to finish draining and end. Used by LogBus.close() and tests.
    suspend fun join() {
        job.join()
    }

    companion object {
        // Big enough that normal apps never drop; small enough to bound memory if a route stalls.
        const val DEFAULT_CAPACITY = 1024
    }
}
