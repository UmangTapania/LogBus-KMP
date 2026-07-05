package com.github.umangtapania.logbus

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A route that records every (finished) event it receives, for inspection. */
internal class RecordingRoute(formatter: LogFormatter? = null) : LogRoute(formatter) {
    val events = mutableListOf<LogEvent>()
    override suspend fun emit(event: LogEvent) {
        events.add(event)
    }
}

/** A route that always throws, to prove one broken route can't stop the others. */
internal class BrokenRoute : LogRoute() {
    override suspend fun emit(event: LogEvent) {
        throw RuntimeException("boom")
    }
}

/** A route that only accepts events at or above [min] — exercises [LogRoute.isLoggable]. */
internal class LevelFilterRoute(private val min: LogLevel) : LogRoute() {
    val events = mutableListOf<LogEvent>()
    override fun isLoggable(event: LogEvent): Boolean = event.level.priority >= min.priority
    override suspend fun emit(event: LogEvent) {
        events.add(event)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LogBusTest {

    @Test
    fun defaultTagAppliedWhenNoneGiven() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute()
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.i("no tag here")
        advanceUntilIdle()

        assertEquals(1, route.events.size)
        assertEquals("LogBus", route.events.single().tag)
    }

    @Test
    fun explicitTagPreserved() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute()
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.i("with tag", tag = "Checkout")
        advanceUntilIdle()

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
        advanceUntilIdle()

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
        advanceUntilIdle()

        assertEquals(42, route.events.single().metadata["userId"])
    }

    @Test
    fun interceptorReturningNullDropsLog() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute()
        val dropAll = LogInterceptor { null }
        val bus = LogBus(listOf(route), listOf(dropAll), this)

        bus.i("should vanish")
        advanceUntilIdle()

        assertTrue(route.events.isEmpty())
    }

    @Test
    fun noRoutesIsANoOp() = runTest(StandardTestDispatcher()) {
        val bus = LogBus(emptyList(), emptyList(), this)

        // Must not throw and must simply do nothing.
        bus.i("into the void")
        advanceUntilIdle()
    }

    @Test
    fun brokenRouteDoesNotStopOthers() = runTest(StandardTestDispatcher()) {
        val good = RecordingRoute()
        val bus = LogBus(listOf(BrokenRoute(), good), emptyList(), this)

        bus.i("survives")
        advanceUntilIdle()

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
        advanceUntilIdle()

        val event = route.events.single()
        assertEquals(analytics, event.level)
        assertEquals(350, event.level.priority)
        assertNull(event.level.emoji)
    }
}
