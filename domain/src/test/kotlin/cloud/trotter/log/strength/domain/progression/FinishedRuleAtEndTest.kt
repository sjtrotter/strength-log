package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.records.CardioBests
import cloud.trotter.log.strength.domain.records.GoalProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinishedRuleAtEndTest {
    private fun time(seconds: Int) = CardioSetSpec(CardioRole.WORK, Measure.Time(seconds))
    private fun distance(metres: Int) = CardioSetSpec(CardioRole.WORK, Measure.Distance(metres))
    private fun block(vararg sets: CardioSetSpec) = CardioBlock(CardioActivity.ROAD_RUN, sets.toList())
    private fun result(
        spec: CardioSetSpec,
        status: CardioSetStatus = CardioSetStatus.FINISHED,
        elapsed: Int = 900,
        metres: Int? = null,
        metresAtEnd: Int? = null,
        secondsAtEnd: Int? = null,
    ) = CardioSetResult(spec, status, elapsed, metres, metres?.let { DistanceSource.MANUAL }, metresAtEnd, secondsAtEnd)

    @Test
    fun `case 18 a distance end reached at the tap reads its time`() {
        val row = FinishedRule.atEnd(result(distance(2414), elapsed = 750, metres = 2414))
        assertEquals(2414, row.metresAtEnd)
        assertEquals(750, row.secondsAtEnd)
        assertEquals(true, FinishedRule.finished(row))
        assertEquals(750, FinishedRule.reading(row))
        assertTrue(CardioBests.counts(row))
    }

    @Test
    fun `a time end run exactly reads its distance`() {
        val row = FinishedRule.atEnd(result(time(900), metres = 3470))
        assertEquals(3470, row.metresAtEnd)
        assertEquals(900, row.secondsAtEnd)
        assertEquals(3470, FinishedRule.reading(row))
        assertTrue(CardioBests.counts(row))
    }

    @Test
    fun `a time end with no distance entered reads its time only`() {
        val row = FinishedRule.atEnd(result(time(900)))
        assertNull(row.metresAtEnd)
        assertEquals(900, row.secondsAtEnd)
        assertEquals(true, FinishedRule.finished(row))
        assertNull(FinishedRule.reading(row))
        assertFalse(CardioBests.counts(row))
        val recovery = FinishedRule.atEnd(result(time(120).copy(role = CardioRole.RECOVERY), elapsed = 120))
        assertNull(recovery.metresAtEnd)
        assertEquals(120, recovery.secondsAtEnd)
    }

    @Test
    fun `case 18 a steady run past its end keeps its time and no distance`() {
        val row = result(time(900), elapsed = 904, metres = 3485)
        val atEnd = FinishedRule.atEnd(row)
        assertNull(atEnd.metresAtEnd)
        assertEquals(900, atEnd.secondsAtEnd)
        assertFalse(CardioBests.counts(atEnd))
        assertEquals(904, atEnd.elapsedSeconds)
        assertEquals(3485, atEnd.metres)
        assertEquals(4, atEnd.elapsedSeconds - requireNotNull(atEnd.secondsAtEnd))
        val aimed = CardioSetSpec(CardioRole.WORK, Measure.Time(900), Measure.Distance(3480))
        val withAim = row.copy(spec = aimed)
        assertTrue(CardioMeasures.fullSuccess(block(aimed), listOf(withAim)))
        assertFalse(CardioMeasures.fullSuccess(block(aimed), listOf(FinishedRule.atEnd(withAim))))
        val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960)
        assertEquals(904, GoalProgress.bestTimed(goal, listOf(CardioActivity.ROAD_RUN to listOf(row))))
        assertNull(GoalProgress.bestTimed(goal, listOf(CardioActivity.ROAD_RUN to listOf(atEnd))))
    }

    @Test
    fun `a set that did not reach its end reads nothing`() {
        val rows = listOf(
            result(time(900), elapsed = 890),
            result(distance(400), CardioSetStatus.SHORTENED, elapsed = 60, metres = 300),
            result(time(300), CardioSetStatus.SKIPPED, elapsed = 0),
        )
        rows.map(FinishedRule::atEnd).forEach {
            assertNull(it.metresAtEnd)
            assertNull(it.secondsAtEnd)
            assertNull(FinishedRule.finished(it))
        }
    }

    @Test
    fun `derived readings recompute and supplied readings are kept`() {
        val derived = FinishedRule.atEnd(result(time(900), metres = 3470))
        val edited = FinishedRule.afterEdit(derived, derived.copy(metres = 3500))
        assertEquals(3500, edited.metresAtEnd)
        assertEquals(900, edited.secondsAtEnd)

        val supplied = result(time(900), elapsed = 904, metres = 3485, metresAtEnd = 3470, secondsAtEnd = 900)
            .copy(readingSource = ReadingSource.TYPED)
        assertEquals(supplied.copy(metres = 3500), FinishedRule.afterEdit(supplied, supplied.copy(metres = 3500)))

        val distanceReading = result(distance(2414), elapsed = 761, metres = 2414, metresAtEnd = 2414, secondsAtEnd = 750)
            .copy(readingSource = ReadingSource.TYPED)
        assertEquals(distanceReading.copy(metres = 2500),
            FinishedRule.afterEdit(distanceReading, distanceReading.copy(metres = 2500)))
    }
}
