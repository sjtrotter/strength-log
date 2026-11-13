package cloud.trotter.log.strength.run

import cloud.trotter.log.strength.domain.cardio.Fix
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.GpsSet
import kotlinx.coroutines.launch
import cloud.trotter.log.strength.ui.day.CardioClock
import kotlin.math.PI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal class FakeLocationSource(var access: LocationAccess = LocationAccess.PRECISE) : LocationSource {
    val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
    var accessCalls = 0
    override fun access(): LocationAccess { accessCalls++; return access }
    override fun updates() = fixes
}

internal class FakeForeground : TrackingForeground {
    var starts = 0
    var stops = 0
    var fail = false
    override fun start(): Boolean { starts++; return !fail }
    override fun stop() { stops++ }
}

internal fun north(t: Long, metres: Double = 3.0 * t) = Fix(t * 1000, metres / (6_371_000.0 * PI / 180.0), 0.0, 5.0)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunTrackerTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeLocationSource()
    private val foreground = FakeForeground()
    private var elapsed = 0L
    private val clock = object : CardioClock {
        override fun wallMillis() = elapsed
        override fun elapsedRealtimeMillis() = elapsed
        override fun bootCount() = 0
    }
    private val tracker = RunTracker(source, foreground, clock, dispatcher)

    @Test fun `precise begins a foreground run and accepts a live fix`() = runTest(dispatcher) {
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        runCurrent()
        assertEquals(1, foreground.starts)
        assertEquals(GpsState.Tracking("r1"), tracker.state.value)
        elapsed = 1000
        source.fixes.emit(north(1))
        runCurrent()
        assertEquals(GpsLiveStatus.LIVE, tracker.live("r1", 0, elapsed)?.status)
        tracker.endAll()
    }

    @Test fun `denied asks without starting or exposing a meter`() = runTest(dispatcher) {
        source.access = LocationAccess.DENIED
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        assertEquals(GpsState.NeedsPermission("r1"), tracker.state.value)
        assertEquals(0, foreground.starts)
        assertNull(tracker.meter("r1"))
    }

    @Test fun `unavailable warm up stays off without collecting locations`() = runTest(dispatcher) {
        source.access = LocationAccess.UNAVAILABLE
        tracker.warmUp(CardioActivity.ROAD_RUN)
        tracker.refreshAccess()
        runCurrent()
        assertEquals(GpsState.Off, tracker.state.value)
        assertEquals(0, source.fixes.subscriptionCount.value)
        assertEquals(0, foreground.starts)
        assertNull(tracker.meter("r1"))
    }

    @Test fun `unavailable begin stays off without collecting locations`() = runTest(dispatcher) {
        source.access = LocationAccess.UNAVAILABLE
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        tracker.refreshAccess()
        runCurrent()
        assertEquals(GpsState.Off, tracker.state.value)
        assertEquals(0, source.fixes.subscriptionCount.value)
        assertEquals(0, foreground.starts)
        assertNull(tracker.meter("r1"))
    }

    @Test fun `location off can begin once precise becomes available`() = runTest(dispatcher) {
        source.access = LocationAccess.LOCATION_OFF
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        assertEquals(GpsState.LocationOff("r1"), tracker.state.value)
        source.access = LocationAccess.PRECISE
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        assertEquals(GpsState.Tracking("r1"), tracker.state.value)
        assertEquals(1, foreground.starts)
        tracker.endAll()
    }

    @Test fun `end ignores another owner and stops its own run once`() = runTest(dispatcher) {
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        tracker.end("other")
        assertEquals(GpsState.Tracking("r1"), tracker.state.value)
        assertEquals(0, foreground.stops)
        tracker.end("r1")
        assertEquals(1, foreground.stops)
        assertEquals(GpsState.Off, tracker.state.value)
        assertNull(tracker.meter("r1"))
    }

    @Test fun `access reads leave tracker state unchanged`() = runTest(dispatcher) {
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        source.access = LocationAccess.LOCATION_OFF
        assertEquals(LocationAccess.LOCATION_OFF, tracker.access())
        assertEquals(GpsState.Tracking("r1"), tracker.state.value)
        tracker.refreshAccess()
        source.access = LocationAccess.PRECISE
        assertEquals(LocationAccess.PRECISE, tracker.access())
        assertEquals(GpsState.LocationOff("r1"), tracker.state.value)
        tracker.endAll()
    }

    @Test fun `location returning mid run resumes live tracking with the same meter`() = runTest(dispatcher) {
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        runCurrent()
        source.fixes.emit(north(0))
        runCurrent()
        val meter = requireNotNull(tracker.meter("r1"))

        source.access = LocationAccess.LOCATION_OFF
        tracker.refreshAccess()
        assertEquals(GpsState.LocationOff("r1"), tracker.state.value)
        assertNull(tracker.live("r1", 0, elapsed))

        elapsed = 32_000
        source.access = LocationAccess.PRECISE
        tracker.refreshAccess()
        source.fixes.emit(north(32))
        runCurrent()

        assertEquals(GpsState.Tracking("r1"), tracker.state.value)
        assertSame(meter, tracker.meter("r1"))
        val live = requireNotNull(tracker.live("r1", 0, elapsed))
        assertEquals(GpsLiveStatus.LIVE, live.status)
        assertEquals(96, live.metres)
        assertEquals(3.0, requireNotNull(live.metresPerSecond), 0.01)
        assertEquals(1, foreground.starts)
        assertEquals(0, foreground.stops)
        tracker.endAll()
    }

    @Test fun `a fix older than ten seconds leaves the last total without speed`() = runTest(dispatcher) {
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        runCurrent()
        source.fixes.emit(north(1))
        runCurrent()
        assertEquals(GpsLive(GpsLiveStatus.NO_SIGNAL, 0, null), tracker.live("r1", 0, 11_001))
        tracker.endAll()
    }

    @Test fun `another run replaces the previous foreground owner`() = runTest(dispatcher) {
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        tracker.begin("r2", CardioActivity.ROAD_RUN, 0)
        assertEquals(1, foreground.stops)
        assertEquals(2, foreground.starts)
        assertNull(tracker.meter("r1"))
        tracker.endAll()
    }

    @Test fun `begin adopts warm up fixes without a foreground warm up`() = runTest(dispatcher) {
        tracker.warmUp(CardioActivity.ROAD_RUN)
        runCurrent()
        for (t in 0L..20L) { elapsed = t * 1000; source.fixes.emit(north(t)); runCurrent() }
        assertEquals(0, foreground.starts)
        tracker.begin("r1", CardioActivity.ROAD_RUN, 20_000)
        assertNotNull(tracker.meter("r1")?.measure(CardioSetSpec(CardioRole.WORK, Measure.Time(10)), 5000, 15_000))
        tracker.endAll()
    }

    @Test fun `warm up and cool down never stop a tracked run`() = runTest(dispatcher) {
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        tracker.warmUp(CardioActivity.ROAD_RUN)
        tracker.coolDown()
        assertEquals(GpsState.Tracking("r1"), tracker.state.value)
        assertEquals(0, foreground.stops)
        tracker.endAll()
    }

    @Test fun `foreground refusal drops the meter and location subscription`() = runTest(dispatcher) {
        foreground.fail = true
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        runCurrent()
        assertEquals(GpsState.Off, tracker.state.value)
        assertNull(tracker.meter("r1"))
        assertEquals(0, source.fixes.subscriptionCount.value)
    }
    @Test fun `cooling down location off stops without further access reads`() = runTest(dispatcher) {
        source.access = LocationAccess.LOCATION_OFF
        tracker.warmUp(CardioActivity.ROAD_RUN)
        assertEquals(GpsState.LocationOff(null), tracker.state.value)
        val calls = source.accessCalls
        tracker.coolDown()
        assertEquals(GpsState.Off, tracker.state.value)
        runCurrent()
        assertEquals(calls, source.accessCalls)
    }

    @Test fun `beginning the same run with location off preserves its track and service`() = runTest(dispatcher) {
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        runCurrent()
        for (t in 0L..20L) { elapsed = t * 1000; source.fixes.emit(north(t)); runCurrent() }
        source.access = LocationAccess.LOCATION_OFF
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        assertEquals(GpsState.LocationOff("r1"), tracker.state.value)
        assertEquals(0, foreground.stops)
        assertEquals(1, foreground.starts)
        source.access = LocationAccess.PRECISE
        tracker.refreshAccess()
        for (t in 21L..40L) { elapsed = t * 1000; source.fixes.emit(north(t)); runCurrent() }
        assertEquals(GpsState.Tracking("r1"), tracker.state.value)
        assertEquals(GpsSet(84, 84, true), requireNotNull(tracker.meter("r1"))
            .measure(CardioSetSpec(CardioRole.WORK, Measure.Time(30)), 0, 30_000))
        tracker.endAll()
    }

    @Test fun `a distance watch emits once at its crossing and clears on null`() = runTest(dispatcher) {
        val reached = mutableListOf<String>()
        backgroundScope.launch { tracker.reached.collect { reached += it } }
        tracker.begin("r1", CardioActivity.ROAD_RUN, clock.wallMillis())
        tracker.watch("r1", 0, 400)
        runCurrent()
        for (t in 0L..135L) { elapsed = t * 1000; source.fixes.emit(north(t)); runCurrent() }
        assertEquals(emptyList<String>(), reached)
        elapsed = 136_000
        source.fixes.emit(north(136))
        runCurrent()
        assertEquals(listOf("r1"), reached)
        for (t in 137L..140L) { elapsed = t * 1000; source.fixes.emit(north(t)); runCurrent() }
        assertEquals(listOf("r1"), reached)
        tracker.watch("r1", null, null)
        for (t in 141L..150L) { elapsed = t * 1000; source.fixes.emit(north(t)); runCurrent() }
        assertEquals(listOf("r1"), reached)
        tracker.endAll()
    }

    @Test fun `route capture keeps only opted in anchor moves and survives the idle end`() = runTest(dispatcher) {
        tracker.begin("r1", CardioActivity.ROAD_RUN, clock.wallMillis())
        tracker.keepRoute("r1", true)
        runCurrent()
        for (t in 0L..20L) { elapsed = t * 1000; source.fixes.emit(north(t)); runCurrent() }
        assertEquals(listOf(0, 4, 8, 12, 16, 20), tracker.route("r1").map { it.seconds })
        assertEquals(listOf(0, 11, 22, 32, 43, 54), tracker.route("r1").map { it.latitudeE5 })
        assertEquals(List(6) { 0 }, tracker.route("r1").map { it.longitudeE5 })
        tracker.end("r1")
        assertEquals(6, tracker.route("r1").size)
        tracker.keepRoute("r1", false)
        assertEquals(emptyList<Int>(), tracker.route("r1").map { it.seconds })
        tracker.begin("r1", CardioActivity.ROAD_RUN, clock.wallMillis())
        runCurrent()
        for (t in 21L..24L) { elapsed = t * 1000; source.fixes.emit(north(t)); runCurrent() }
        assertEquals(emptyList<Int>(), tracker.route("r1").map { it.seconds })
        tracker.keepRoute("r1", true)
        for (t in 25L..28L) { elapsed = t * 1000; source.fixes.emit(north(t)); runCurrent() }
        assertEquals(1, tracker.route("r1").size)
        tracker.begin("r2", CardioActivity.ROAD_RUN, clock.wallMillis())
        assertEquals(emptyList<Int>(), tracker.route("r1").map { it.seconds })
        tracker.endAll()
    }

    @Test fun `last fix exposes the accepted fix elapsed time`() = runTest(dispatcher) {
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        runCurrent()
        source.fixes.emit(north(20))
        runCurrent()
        assertEquals(20_000L, tracker.lastFix.value)
        tracker.endAll()
    }

    @Test fun `route seconds keep their elapsed origin after a backward wall clock step`() = runTest(dispatcher) {
        var wallOffset = 0L
        val jumpingClock = object : CardioClock by clock {
            override fun wallMillis() = elapsed + wallOffset
        }
        val tracker = RunTracker(source, foreground, jumpingClock, dispatcher)
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        tracker.keepRoute("r1", true)
        runCurrent()
        for (t in 0L..2L) { elapsed = t * 1000; source.fixes.emit(north(t, 11.0 * t)); runCurrent() }
        wallOffset = -2_000
        tracker.begin("r1", CardioActivity.ROAD_RUN, 0)
        elapsed = 3_000
        source.fixes.emit(north(3, 33.0))
        runCurrent()
        assertEquals(listOf(0, 1, 2, 3), tracker.route("r1").map { it.seconds })
        tracker.endAll()
    }

}
