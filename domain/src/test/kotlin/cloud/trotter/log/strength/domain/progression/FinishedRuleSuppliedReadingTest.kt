package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.records.CardioBests
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
        assertEquals(row.copy(metresAtEnd = 5050, secondsAtEnd = 1500), supplied)
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
        assertEquals(row.copy(status = CardioSetStatus.FINISHED, metresAtEnd = 2414, secondsAtEnd = 900), supplied)
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
}
