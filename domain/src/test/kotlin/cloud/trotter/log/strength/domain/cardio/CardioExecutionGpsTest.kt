package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.GpsSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ReadingSource
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardioExecutionGpsTest {
    private val start = Clocks(1_000_000, 0, 7)
    private fun now(millis: Long) = start.copy(wallMillis = start.wallMillis + millis, elapsedMillis = millis)
    private fun time(seconds: Int, role: CardioRole = CardioRole.WORK) = CardioSetSpec(role, Measure.Time(seconds))
    private fun distance(metres: Int) = CardioSetSpec(CardioRole.WORK, Measure.Distance(metres))
    private fun execution(vararg sets: CardioSetSpec, openEnded: Boolean = false) =
        CardioExecution(CardioBlock(CardioActivity.ROAD_RUN, sets.toList()), allowOpenEnded = openEnded).start(start)
    private fun steady(first: Long = 0, last: Long = 2000) = DistanceTrack(12.0).apply {
        for (t in first..last) add(Fix(t * 1000, 3.0 * t / (6_371_000.0 * PI / 180.0), 0.0, 5.0))
    }

    @Test fun `reconcile measures exact deadlines and automatic recovery`() {
        val exec = execution(time(300), time(120, CardioRole.RECOVERY))
        val run = exec.reconcile(now(430_000), steady())
        assertTrue(run.complete)
        assertEquals(listOf(300, 120), run.results.map { it.elapsedSeconds })
        assertEquals(listOf(900, 360), run.results.map { it.metres })
        assertEquals(listOf(900 to 300, 360 to 120), run.results.map { it.metresAtEnd to it.secondsAtEnd })
        assertTrue(run.results.all { it.status == CardioSetStatus.FINISHED && it.distanceSource == DistanceSource.GPS &&
            it.readingSource == ReadingSource.GPS })
        assertTrue(run.distanceEntries.isEmpty())
        assertEquals(exec.reconcile(now(430_000)), exec.reconcile(now(430_000), null))
    }

    @Test fun `reconcile waits for a fix but retains the exact next anchor`() {
        val exec = execution(time(300), time(120, CardioRole.RECOVERY))
        val meter = steady(last = 299)
        val waiting = exec.reconcile(now(300_500), meter)
        assertEquals(exec, waiting)
        assertTrue(waiting.running)
        assertTrue(waiting.results.isEmpty())
        val finished = exec.reconcile(now(310_000), meter)
        val row = finished.results.single()
        assertEquals(300, row.elapsedSeconds)
        assertEquals(888, row.metres)
        assertEquals(DistanceSource.GPS, row.distanceSource)
        assertEquals(null to 300, row.metresAtEnd to row.secondsAtEnd)
        assertEquals(300_000L, finished.anchor?.elapsedMillis)
        assertEquals(finished, exec.reconcile(now(300_500), meter, waitForFix = false))
    }

    @Test fun `reached uses GPS only when it passes the distance tap margin`() {
        val exec = execution(distance(1000))
        val passed = exec.reachTarget(now(350_000), steady()).results.single()
        assertEquals(CardioSetStatus.FINISHED, passed.status)
        assertEquals(350, passed.elapsedSeconds)
        assertEquals(1044, passed.metres)
        assertEquals(DistanceSource.GPS, passed.distanceSource)
        assertEquals(1000 to 335, passed.metresAtEnd to passed.secondsAtEnd)
        assertEquals(ReadingSource.GPS, passed.readingSource)
        val tapped = exec.reachTarget(now(340_000), steady()).results.single()
        assertEquals(1000, tapped.metres)
        assertEquals(DistanceSource.MANUAL, tapped.distanceSource)
        assertEquals(null to null, tapped.metresAtEnd to tapped.secondsAtEnd)
        assertEquals(null, tapped.readingSource)
    }

    @Test fun `short distance ends keep the manual tap`() {
        val row = execution(distance(300)).reachTarget(now(150_000), steady()).results.single()
        assertEquals(300, row.metres)
        assertEquals(DistanceSource.MANUAL, row.distanceSource)
    }

    @Test fun `an open ended finish uses GPS total and its planned reading without an entry`() {
        val exec = execution(time(1800), openEnded = true)
        val clocks = now(1_900_000)
        val run = exec.finishStep(CardioSetStatus.FINISHED, 1900, clocks, gps = exec.measure(steady(), clocks))
        val row = run.results.single()
        assertEquals(5700, row.metres)
        assertEquals(DistanceSource.GPS, row.distanceSource)
        assertEquals(5400 to 1800, row.metresAtEnd to row.secondsAtEnd)
        assertEquals(ReadingSource.GPS, row.readingSource)
        assertTrue(run.distanceEntries.isEmpty())
        assertEquals(listOf(0), exec.finishStep(CardioSetStatus.FINISHED, 1900, clocks).distanceEntries)
    }

    @Test fun `a partial stopped set always requests an entry even at end block`() {
        val exec = execution(time(600))
        val clocks = now(200_000)
        val complete = exec.finishStep(CardioSetStatus.SHORTENED, 200, clocks, gps = exec.measure(steady(), clocks))
        assertEquals(600, complete.results.single().metres)
        assertEquals(DistanceSource.GPS, complete.results.single().distanceSource)
        assertTrue(complete.distanceEntries.isEmpty())
        val gps = exec.measure(steady(first = 20), clocks)
        assertEquals(GpsSet(540, null, false), gps)
        val partial = exec.finishStep(CardioSetStatus.SHORTENED, 200, clocks, gps = gps)
        assertEquals(null, partial.results.single().metres)
        assertEquals(listOf(0), partial.distanceEntries)
        assertEquals(mapOf(0 to GpsSet(540, null, false)), partial.measured)
        assertEquals(listOf(0), exec.finishStep(CardioSetStatus.SHORTENED, 200, clocks,
            requestDistance = false, gps = gps).distanceEntries)
    }

    @Test fun `measured records what finish received and leaves a nonoverridden tap unstamped`() {
        val exec = execution(distance(1000))
        assertEquals(mapOf(0 to GpsSet(1044, 335, true)), exec.reachTarget(now(350_000), steady()).measured)
        assertTrue(exec.reachTarget(now(340_000), steady()).measured.isEmpty())
        assertEquals(null, exec.copy(anchor = null).measure(steady(), now(350_000)))
        val complete = exec.reachTarget(now(350_000), steady())
        assertFalse(complete.running)
        assertEquals(null, complete.measure(steady(), now(350_000)))
    }
}
