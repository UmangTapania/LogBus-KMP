package io.github.umangtapania.logbus

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An async route that records every (finished) event it receives, for inspection. */
internal class RecordingRoute(formatter: LogFormatter? = null) : AsyncLogRoute(formatter) {
    val events = mutableListOf<LogEvent>()
    override suspend fun emit(event: LogEvent) {
        events.add(event)
    }
}

/** An async route that always throws, to prove one broken route can't stop the others. */
internal class BrokenRoute : AsyncLogRoute() {
    override suspend fun emit(event: LogEvent) {
        throw RuntimeException("boom")
    }
}

/** A route whose emit blocks (virtually) for [delayMillis] — used to prove slow routes stay isolated. */
internal class SlowRoute(private val delayMillis: Long) : AsyncLogRoute() {
    val events = mutableListOf<LogEvent>()
    override suspend fun emit(event: LogEvent) {
        delay(delayMillis)
        events.add(event)
    }
}

/** An async route that only accepts events at or above [min] — exercises [LogRoute.isLoggable]. */
internal class LevelFilterRoute(private val min: LogLevel) : AsyncLogRoute() {
    val events = mutableListOf<LogEvent>()
    override fun isLoggable(event: LogEvent): Boolean = event.level.priority >= min.priority
    override suspend fun emit(event: LogEvent) {
        events.add(event)
    }
}

/** A sync route that records inline, no worker — proves synchronous, on-caller-thread delivery. */
internal class SyncRecordingRoute(formatter: LogFormatter? = null) : SyncLogRoute(formatter) {
    val events = mutableListOf<LogEvent>()
    override fun emit(event: LogEvent) {
        events.add(event)
    }
}

/** A sync route that always throws, to prove a broken sync route can't crash the caller or block others. */
internal class BrokenSyncRoute : SyncLogRoute() {
    override fun emit(event: LogEvent) {
        throw RuntimeException("boom")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LogBusTest {

    @Test
    fun defaultTagAppliedWhenNoneGiven() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute()
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.i("no tag here")
        bus.close()

        assertEquals(1, route.events.size)
        assertEquals("LogBus", route.events.single().tag)
    }

    @Test
    fun explicitTagPreserved() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute()
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.i("with tag", tag = "Checkout")
        bus.close()

        assertEquals("Checkout", route.events.single().tag)
    }

    @Test
    fun shortcutsMapToRightLevelsAndErrorThreadsThrough() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute()
        val bus = LogBus(listOf(route), emptyList(), this)
        val boom = IllegalStateException("bad")

        bus.v("v")
        bus.d("d")
        bus.i("i")
        bus.w("w")
        bus.e("e", error = boom)
        bus.close()

        assertEquals(
            listOf(
                DefaultLevel.VERBOSE,
                DefaultLevel.DEBUG,
                DefaultLevel.INFO,
                DefaultLevel.WARNING,
                DefaultLevel.ERROR,
            ),
            route.events.map { it.level },
        )
        assertEquals(boom, route.events.last().error)
    }

    @Test
    fun interceptorCanModifyEvent() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute()
        val addUser = LogInterceptor { event -> event.copy(metadata = event.metadata + ("userId" to 42)) }
        val bus = LogBus(listOf(route), listOf(addUser), this)

        bus.i("hi")
        bus.close()

        assertEquals(42, route.events.single().metadata["userId"])
    }

    @Test
    fun interceptorReturningNullDropsLog() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute()
        val dropAll = LogInterceptor { null }
        val bus = LogBus(listOf(route), listOf(dropAll), this)

        bus.i("should vanish")
        bus.close()

        assertTrue(route.events.isEmpty())
    }

    @Test
    fun noRoutesIsANoOp() = runTest(StandardTestDispatcher()) {
        val bus = LogBus(emptyList(), emptyList(), this)

        // Must not throw and must simply do nothing.
        bus.i("into the void")
        bus.close()
    }

    @Test
    fun brokenRouteDoesNotStopOthers() = runTest(StandardTestDispatcher()) {
        val good = RecordingRoute()
        val bus = LogBus(listOf(BrokenRoute(), good), emptyList(), this)

        bus.i("survives")
        bus.close()

        assertEquals(1, good.events.size)
        assertEquals("survives", good.events.single().message)
    }

    @Test
    fun customLevelWorks() = runTest(StandardTestDispatcher()) {
        val analytics = object : LogLevel {
            override val name = "ANALYTICS"
            override val priority = 350
            override val emoji: String? = null
        }
        val route = RecordingRoute()
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.log(analytics, "checkout done")
        bus.close()

        val event = route.events.single()
        assertEquals(analytics, event.level)
        assertEquals(350, event.level.priority)
        assertNull(event.level.emoji)
    }

    @Test
    fun logsArriveInOrder() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute()
        val bus = LogBus(listOf(route), emptyList(), this)

        // Under StandardTestDispatcher the worker doesn't run until we suspend, so all 500 sends land
        // in the mailbox (capacity 1024) before any draining — none are dropped.
        val sent = (0 until 500).map { "msg-$it" }
        sent.forEach { bus.i(it) }
        bus.close()

        assertEquals(sent, route.events.map { it.message })
    }

    @Test
    fun slowRouteDoesNotHoldUpFastRoute() = runTest(StandardTestDispatcher()) {
        val slow = SlowRoute(delayMillis = 10_000)
        val fast = RecordingRoute()
        val bus = LogBus(listOf(slow, fast), emptyList(), this)

        bus.i("x")
        // Run only what's ready at the current virtual time: the fast worker records immediately; the
        // slow worker enters emit() and parks on its 10s delay. No time is advanced.
        runCurrent()

        assertEquals(1, fast.events.size)      // fast route delivered without waiting for slow
        assertTrue(slow.events.isEmpty())      // slow route still mid-delay

        // Now drain everything (close advances virtual time through the slow delay).
        bus.close()
        assertEquals(1, slow.events.size)
    }

    @Test
    fun syncRouteDeliversInlineBeforeCallReturns() = runTest(StandardTestDispatcher()) {
        val route = SyncRecordingRoute()
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.i("now")
        // No bus.close(), no dispatcher advance: a sync route runs inline on the calling thread, so the
        // event is already recorded. (An AsyncLogRoute would still be sitting in its mailbox here.)
        assertEquals(1, route.events.size)
        assertEquals("now", route.events.single().message)

        bus.close()
    }

    @Test
    fun asyncRouteDoesNotDeliverInline() = runTest(StandardTestDispatcher()) {
        // Counterpart to the test above: proves the sync route's inline delivery is a real difference,
        // not something every route does. The async worker hasn't run yet at this point.
        val route = RecordingRoute()
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.i("later")
        assertTrue(route.events.isEmpty())

        bus.close()
        assertEquals(1, route.events.size)
    }

    @Test
    fun brokenSyncRouteDoesNotCrashCallerOrStopOthers() = runTest(StandardTestDispatcher()) {
        val good = SyncRecordingRoute()
        val bus = LogBus(listOf(BrokenSyncRoute(), good), emptyList(), this)

        bus.i("survives") // must not throw, even though the first sync route throws inline
        assertEquals(1, good.events.size)
        assertEquals("survives", good.events.single().message)

        bus.close()
    }

    @Test
    fun syncRouteIgnoresLogsAfterClose() = runTest(StandardTestDispatcher()) {
        val route = SyncRecordingRoute()
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.i("before")
        bus.close()
        bus.i("after") // gated by closed flag → ignored

        assertEquals(listOf("before"), route.events.map { it.message })
    }
}
