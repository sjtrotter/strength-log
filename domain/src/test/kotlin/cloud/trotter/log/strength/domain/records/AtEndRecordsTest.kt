package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.progression.FinishedRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtEndRecordsTest {
    private val activity = CardioActivity.ROAD_RUN
    private fun row(end: Measure, seconds: Int, metres: Int? = null) =
        CardioSetResult(CardioSetSpec(CardioRole.WORK, end), CardioSetStatus.FINISHED, seconds, metres, metres?.let { DistanceSource.MANUAL })

    @Test fun `a timed set counts at its reading at the end and the overrun never counts`() {
        val end = Measure.Time(900)
        val result = row(end, 904, 3485).copy(metresAtEnd = 3470, secondsAtEnd = 900)
        assertTrue(CardioBests.counts(result))
        assertEquals(3470, FinishedRule.reading(result))
        val record = CardioBests.records(activity, listOf(result), CardioBests.bests(activity, listOf(row(end, 900, 3400)))).single()
        assertEquals(PrKind.FARTHER, record.kind)
        assertTrue(CardioBests.records(activity, listOf(result), CardioBests.bests(activity, listOf(row(end, 900, 3480)))).isEmpty())
        assertFalse(CardioBests.counts(result.copy(metresAtEnd = null, secondsAtEnd = null)))
        assertFalse(CardioBests.counts(result.copy(status = CardioSetStatus.SHORTENED)))
    }

    @Test fun `a distance set counts at its time at the end`() {
        val end = Measure.Distance(2414)
        val result = row(end, 761).copy(secondsAtEnd = 750, metresAtEnd = 2414)
        val standing = CardioBests.bests(activity, listOf(row(end, 760)))
        assertEquals(PrKind.FASTER, CardioBests.records(activity, listOf(result), standing).single().kind)
        assertTrue(CardioBests.records(activity, listOf(result.copy(secondsAtEnd = null, metresAtEnd = null)), standing).isEmpty())
    }

    @Test fun `goal progress reads the time at the end`() {
        val goal = PlanGoal.Timed(activity, 3219, 960)
        val result = row(Measure.Distance(3219), 963).copy(secondsAtEnd = 955, metresAtEnd = 3219)
        fun best(row: CardioSetResult) = GoalProgress.bestTimed(goal, listOf(activity to listOf(row)))
        assertEquals(955, best(result))
        assertTrue(GoalProgress.met(goal, best(result)))
        assertEquals(963, best(result.copy(secondsAtEnd = null, metresAtEnd = null)))
        assertFalse(GoalProgress.met(goal, 963))
        assertNull(best(result.copy(status = CardioSetStatus.SHORTENED)))
    }
}
