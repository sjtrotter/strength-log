package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.GpsSet
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DistanceTrackTest {
    private fun north(t: Long, metres: Double, accuracy: Double = 5.0) =
        Fix(t * 1000, metres / (6_371_000.0 * PI / 180.0), 0.0, accuracy)

    private fun steady(first: Long = 0, last: Long = 2000, keep: (Long) -> Boolean = { true }) =
        DistanceTrack(12.0).apply {
            for (t in first..last) if (keep(t)) add(north(t, 3.0 * t))
        }

    private fun time(seconds: Int) = CardioSetSpec(CardioRole.WORK, Measure.Time(seconds))
    private fun distance(metres: Int) = CardioSetSpec(CardioRole.WORK, Measure.Distance(metres))
    private fun metres(track: DistanceTrack, time: Long) = assertNotNull(track.metresAt(time))

    @Test fun `steady fixes count six anchor moves through twenty seconds`() {
        assertEquals(6, steady(last = 20).anchorMoves)
    }

    @Test fun `steady fixes interpolate totals and planned end readings`() {
        val track = steady()
        assertEquals(900.0, metres(track, 300_000), 0.01)
        assertEquals(906.0, metres(track, 303_500), 0.01)
        assertEquals(GpsSet(900, 900, true), track.measure(time(300), 0, 300_000))
        assertEquals(GpsSet(960, 900, true), track.measure(time(300), 0, 320_000))
        assertEquals(GpsSet(1020, 335, true), track.measure(distance(1000), 0, 340_000))
        assertEquals(GpsSet(984, null, true), track.measure(distance(1000), 0, 330_000))
    }

    @Test fun `inaccurate fixes are rejected and a short gap is bridged`() {
        val track = DistanceTrack(12.0)
        for (t in 0L..400L) {
            val inaccurate = t in 100L..119L
            assertEquals(!inaccurate, track.add(north(t, 3.0 * t, if (inaccurate) 25.0 else 5.0)))
        }
        assertEquals(360.0, metres(track, 120_000), 0.01)
        assertEquals(GpsSet(900, 900, true), track.measure(time(300), 0, 300_000))
    }

    @Test fun `a long gap makes only the set it crosses partial`() {
        val track = steady(keep = { it !in 100L..170L })
        assertEquals(GpsSet(897, null, false), track.measure(time(300), 0, 300_000))
        assertEquals(GpsSet(180, 180, true), track.measure(time(60), 180_000, 240_000))
    }

    @Test fun `a jump is rejected without moving the anchor`() {
        val track = DistanceTrack(12.0)
        for (t in 0L..100L) {
            val accepted = track.add(north(t, if (t == 50L) 500.0 else 3.0 * t))
            assertEquals(t != 50L, accepted)
        }
        assertEquals(180.0, metres(track, 60_000), 0.01)
    }

    @Test fun `a red light adds no distance until movement resumes`() {
        val track = steady(last = 100)
        for (t in 101L..159L) track.add(north(t, if (t % 2 == 1L) 304.0 else 296.0))
        for (t in 160L..300L) track.add(north(t, 300.0 + 3 * (t - 160)))
        assertEquals(0.0, metres(track, 160_000) - metres(track, 100_000), 0.01)
        assertEquals(GpsSet(420, 420, true), track.measure(time(200), 0, 200_000))
    }

    @Test fun `stationary jitter adds no distance`() {
        val track = DistanceTrack(12.0)
        for (t in 0L..600L) track.add(north(t, if (t % 2 == 0L) 5.5 else -5.5, 8.0))
        assertEquals(0.0, metres(track, 600_000) - metres(track, 0), 0.01)
        assertEquals(GpsSet(0, 0, true), track.measure(time(600), 0, 600_000))
    }

    @Test fun `coverage at the edges differs from a fresh start reading`() {
        assertEquals(GpsSet(840, null, false), steady(first = 20, last = 400).measure(time(300), 0, 300_000))
        val late = steady(first = 5, last = 400)
        assertEquals(GpsSet(876, null, true), late.measure(time(300), 0, 300_000))
        assertFalse(late.fresh(0))
        assertEquals(GpsSet(840, null, false), steady(last = 280).measure(time(300), 0, 300_000))
    }

    @Test fun `short distance ends and empty tracks are not measured`() {
        assertNull(steady().measure(distance(300), 0, 150_000))
        val empty = DistanceTrack(12.0)
        assertNull(empty.measure(time(60), 0, 60_000))
        assertNull(empty.metresAt(0))
        assertNull(empty.lastFixMillis)
    }

    @Test fun `rides accept twenty metres per second while runs reject them`() {
        val ride = DistanceTrack.forActivity(CardioActivity.OUTDOOR_RIDE)
        val run = DistanceTrack.forActivity(CardioActivity.ROAD_RUN)
        for (t in 0L..60L) {
            assertTrue(ride.add(north(t, 20.0 * t)))
            assertEquals(t == 0L, run.add(north(t, 20.0 * t)))
        }
        assertEquals(1200.0, metres(ride, 60_000), 0.01)
        assertEquals(0.0, metres(run, 60_000), 0.01)
        assertEquals(60_000L, ride.lastFixMillis)
    }

    @Test fun `speed uses anchor moves and expires while stopped`() {
        val track = steady()
        assertEquals(3.0, assertNotNull(track.metresPerSecond(300_000)), 0.01)
        assertEquals(3.0, assertNotNull(track.metresPerSecond(302_000)), 0.01)
        val walking = DistanceTrack(12.0)
        for (t in 0L..400L) walking.add(north(t, 1.4 * t))
        assertEquals(1.4, assertNotNull(walking.metresPerSecond(304_000)), 0.01)
        val stopped = steady(last = 100)
        for (t in 101L..140L) stopped.add(north(t, 300.0))
        assertNull(stopped.metresPerSecond(140_000))
    }

    @Test fun `a boundary settles on a later fix or after ten seconds`() {
        val track = steady(last = 299)
        assertFalse(track.fresh(300_000))
        assertFalse(track.settled(300_000, 300_500))
        assertTrue(track.settled(300_000, 310_000))
        assertTrue(track.add(north(301, 903.0)))
        assertTrue(track.settled(300_000, 300_500))
        assertTrue(track.fresh(300_000))
    }

    @Test fun `a gap crossing the set end counts only its overlap`() {
        val track = steady(last = 400, keep = { it <= 230 || it >= 301 })
        assertEquals(GpsSet(899, null, false), track.measure(time(300), 0, 300_000))
        assertEquals(GpsSet(180, 180, true), track.measure(time(60), 310_000, 370_000))
    }
}
