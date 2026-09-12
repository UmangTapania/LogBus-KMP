package io.github.umangtapania.logbus

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LogRouteTest {

    @Test
    fun formatterReplacesMessageBeforeEmit() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute(formatter = LogFormatter { "F:${it.message}" })
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.i("hi")
        bus.close()

        assertEquals("F:hi", route.events.single().message)
    }

    @Test
    fun noFormatterLeavesMessageUnchanged() = runTest(StandardTestDispatcher()) {
        val route = RecordingRoute() // no formatter
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.i("hi")
        bus.close()

        assertEquals("hi", route.events.single().message)
    }

    @Test
    fun asyncRouteWorksStandaloneWithoutBus() = runTest {
        // No bus at all — the async route's own suspend log() runs its pipeline (filter → formatter → emit).
        val route = RecordingRoute(formatter = LogFormatter { "F:${it.message}" })

        route.log(LogEvent(DefaultLevel.INFO, tag = "T", message = "hi", timeMillis = 0L))

        assertEquals("F:hi", route.events.single().message)
    }

    @Test
    fun syncRouteWorksStandaloneWithoutBus() {
        // Same pipeline, non-suspend path: a sync route's log() runs standalone with no coroutine at all.
        val route = SyncRecordingRoute(formatter = LogFormatter { "F:${it.message}" })

        route.log(LogEvent(DefaultLevel.INFO, tag = "T", message = "hi", timeMillis = 0L))

        assertEquals("F:hi", route.events.single().message)
    }

    @Test
    fun isLoggableFiltersOutLowerLevels() = runTest(StandardTestDispatcher()) {
        val route = LevelFilterRoute(min = DefaultLevel.WARNING)
        val bus = LogBus(listOf(route), emptyList(), this)

        bus.i("dropped")   // below threshold
        bus.w("kept")      // at threshold
        bus.e("kept too")  // above threshold
        bus.close()

        assertEquals(listOf("kept", "kept too"), route.events.map { it.message })
    }
}