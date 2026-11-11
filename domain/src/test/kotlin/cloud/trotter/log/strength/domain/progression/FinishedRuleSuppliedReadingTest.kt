package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.records.CardioBests
import cloud.trotter.log.strength.domain.records.GoalProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinishedRuleSuppliedReadingTest {
    private val timeEnd = CardioSetSpec(CardioRole.WORK, Measure.Time(1500), Measure.Distance(5000))
    private val distanceEnd = CardioSetSpec(CardioRole.WORK, Measure.Distance(2414), Measure.Time(960))
    private fun block(vararg sets: CardioSetSpec) = CardioBlock(CardioActivity.ROAD_RUN, sets.toList())
    private fun result(
        spec: CardioSetSpec,
        status: CardioSetStatus = CardioSetStatus.FINISHED,
        elapsed: Int = 1690,
        metres: Int? = null,
        metresAtEnd: Int? = null,
        secondsAtEnd: Int? = null,
    ) = CardioSetResult(spec, status, elapsed, metres, metres?.let { DistanceSource.MANUAL }, metresAtEnd, secondsAtEnd)

    @Test
    fun `a set awaits a reading only past its end with none there`() {
        assertTrue(FinishedRule.awaitsReading(result(timeEnd)))
        assertFalse(FinishedRule.awaitsReading(result(timeEnd, metresAtEnd = 5050)))
        assertFalse(FinishedRule.awaitsReading(result(timeEnd, elapsed = 1500)))
        assertFalse(FinishedRule.awaitsReading(result(timeEnd, CardioSetStatus.SHORTENED)))
        val stopped = result(distanceEnd, CardioSetStatus.SHORTENED, 1000, 2736)
        assertTrue(FinishedRule.awaitsReading(stopped))
        assertFalse(FinishedRule.awaitsReading(stopped.copy(metres = 2414)))
        assertFalse(FinishedRule.awaitsReading(stopped.copy(metres = null, distanceSource = null)))
        assertFalse(FinishedRule.awaitsReading(stopped.copy(secondsAtEnd = 900)))
    }

    @Test
    fun `a distance at a time end fills metresAtEnd`() {
        val row = result(timeEnd, metres = 5600)
        val supplied = assertNotNull(FinishedRule.withReading(row, 5050))
        assertEquals(row.copy(metresAtEnd = 5050, secondsAtEnd = 1500, readingSource = ReadingSource.TYPED), supplied)
        assertEquals(1690, supplied.elapsedSeconds)
        assertEquals(5600, supplied.metres)
        assertEquals(5050, FinishedRule.reading(supplied))
        assertTrue(CardioBests.counts(supplied))
        assertTrue(CardioMeasures.fullSuccess(block(timeEnd), listOf(supplied)))
        assertNotNull(FinishedRule.withReading(row, 5600))
        assertNull(FinishedRule.withReading(row, 5601))
        assertNull(FinishedRule.withReading(row, 0))
        assertNull(FinishedRule.withReading(row.copy(metres = null, distanceSource = null), 5050))
    }

    @Test
    fun `a time at a distance end finishes the set`() {
        val row = result(distanceEnd, CardioSetStatus.SHORTENED, 1000, 2736)
        val supplied = assertNotNull(FinishedRule.withReading(row, 900))
        assertEquals(row.copy(status = CardioSetStatus.FINISHED, metresAtEnd = 2414, secondsAtEnd = 900, readingSource = ReadingSource.TYPED), supplied)
        assertEquals(1000, supplied.elapsedSeconds)
        assertEquals(2736, supplied.metres)
        assertEquals(900, FinishedRule.reading(supplied))
        assertTrue(CardioBests.counts(supplied))
        assertTrue(CardioMeasures.fullSuccess(block(distanceEnd), listOf(supplied)))
        assertFalse(CardioMeasures.fullSuccess(block(distanceEnd), listOf(assertNotNull(FinishedRule.withReading(row, 961)))))
        assertNotNull(FinishedRule.withReading(row, 1000))
        assertNull(FinishedRule.withReading(row, 1001))
        assertNull(FinishedRule.withReading(row, 0))
    }

    @Test
    fun `a distance end passed by its total derives no time and earns no record`() {
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105))
        val row = FinishedRule.atEnd(result(spec, elapsed = 100, metres = 401))
        assertEquals(400 to null, row.metresAtEnd to row.secondsAtEnd)
        assertEquals(401, row.metres)
        assertEquals(100, row.elapsedSeconds)
        assertEquals(true, FinishedRule.finished(row))
        assertNull(FinishedRule.reading(row))
        assertTrue(FinishedRule.awaitsReading(row))
        assertFalse(CardioBests.counts(row))
        val standing = CardioBests.bests(CardioActivity.ROAD_RUN, listOf(
            FinishedRule.atEnd(result(spec, elapsed = 105, metres = 400)),
        ))
        assertTrue(CardioBests.records(CardioActivity.ROAD_RUN, listOf(row), standing).isEmpty())
        assertFalse(CardioMeasures.fullSuccess(block(spec), listOf(row)))
        assertNull(GoalProgress.bestTimed(PlanGoal.Timed(CardioActivity.ROAD_RUN, 400, 105),
            listOf(CardioActivity.ROAD_RUN to listOf(row))))
    }

    @Test
    fun `a supplied time equal to the total survives an elapsed time edit`() {
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(400))
        val stopped = result(spec, CardioSetStatus.SHORTENED, elapsed = 100, metres = 401)
        val supplied = assertNotNull(FinishedRule.withReading(stopped, 100))
        val edited = FinishedRule.afterEdit(supplied, supplied.copy(elapsedSeconds = 110))
        assertEquals(supplied.copy(elapsedSeconds = 110), edited)
        assertEquals(400 to 100, edited.metresAtEnd to edited.secondsAtEnd)
        assertEquals(100, FinishedRule.reading(edited))
        assertTrue(CardioBests.counts(edited))
        assertFalse(FinishedRule.awaitsReading(edited))
    }

    @Test
    fun `a typed time survives a distance correction that makes it equal to a derived reading`() {
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(400))
        val stopped = result(spec, CardioSetStatus.SHORTENED, elapsed = 100, metres = 401)
        val supplied = assertNotNull(FinishedRule.withReading(stopped, 100))
        val distanceEdited = FinishedRule.afterEdit(supplied, supplied.copy(metres = 400))
        assertEquals(400 to 100, distanceEdited.metresAtEnd to distanceEdited.secondsAtEnd)
        assertEquals(ReadingSource.TYPED, distanceEdited.readingSource)

        val durationEdited = FinishedRule.afterEdit(distanceEdited, distanceEdited.copy(elapsedSeconds = 110))
        assertEquals(400, durationEdited.metres)
        assertEquals(110, durationEdited.elapsedSeconds)
        assertEquals(400 to 100, durationEdited.metresAtEnd to durationEdited.secondsAtEnd)
        assertEquals(ReadingSource.TYPED, durationEdited.readingSource)
    }

    @Test
    fun `a null source recomputes even when the old readings differ from the totals`() {
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(400))
        val derived = result(spec, elapsed = 100, metres = 400, metresAtEnd = 400, secondsAtEnd = 90)
        val edited = FinishedRule.afterEdit(derived, derived.copy(elapsedSeconds = 110))
        assertEquals(400 to 110, edited.metresAtEnd to edited.secondsAtEnd)
        assertNull(edited.readingSource)
    }

    @Test
    fun `a distance end reached exactly still derives its elapsed time`() {
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(400))
        val row = FinishedRule.atEnd(result(spec, elapsed = 100, metres = 400))
        assertEquals(400 to 100, row.metresAtEnd to row.secondsAtEnd)
        assertEquals(100, FinishedRule.reading(row))
        assertTrue(CardioBests.counts(row))
        assertFalse(FinishedRule.awaitsReading(row))
        assertEquals(row.copy(elapsedSeconds = 110, secondsAtEnd = 110),
            FinishedRule.afterEdit(row, row.copy(elapsedSeconds = 110)))
    }
}
