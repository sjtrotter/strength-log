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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinishedRuleTest {
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
    fun `case 18 time end with overrun`() {
        val row = result(time(900), elapsed = 904, metres = 3485, metresAtEnd = 3470, secondsAtEnd = 900)
        assertEquals(true, FinishedRule.finished(row))
        assertEquals(3470, FinishedRule.reading(row))
        assertEquals(15, requireNotNull(row.metres) - requireNotNull(FinishedRule.reading(row)))
        assertEquals(4, row.elapsedSeconds - requireNotNull(row.secondsAtEnd))
    }

    @Test
    fun `case 18 distance end`() {
        val row = result(distance(2414), elapsed = 761, metresAtEnd = 2414, secondsAtEnd = 750)
        assertEquals(true, FinishedRule.finished(row))
        assertEquals(750, FinishedRule.reading(row))
        assertNull(row.metres)
    }

    @Test
    fun `case 18 stopped early`() {
        val row = result(time(900), CardioSetStatus.SHORTENED, elapsed = 890, metres = 3440)
        assertNull(FinishedRule.finished(row))
        assertFalse(CardioBests.counts(row))
        assertFalse(CardioMeasures.fullSuccess(block(row.spec), listOf(row)))
    }

    @Test
    fun `case 18 pre-v25 rows keep today's rule in each reader`() {
        val row = result(time(900), metres = 3470)
        assertNull(FinishedRule.finished(row))
        assertEquals(3470, FinishedRule.reading(row))
        assertTrue(CardioBests.counts(row))
        val overrun = row.copy(elapsedSeconds = 904, metres = 3485)
        assertNull(FinishedRule.finished(overrun))
        assertEquals(3485, FinishedRule.reading(overrun))
        assertFalse(CardioBests.counts(overrun))
        assertEquals(750, FinishedRule.reading(result(distance(2414), elapsed = 750)))
    }

    @Test
    fun `case 18 hand logged v25 row has one reading which is both`() {
        val row = result(time(900), metres = 3470, metresAtEnd = 3470, secondsAtEnd = 900)
        assertEquals(true, FinishedRule.finished(row))
        assertEquals(3470, FinishedRule.reading(row))
    }

    @Test
    fun `case 18 v25 row with at-end fields that did not finish`() {
        val row = result(time(900), CardioSetStatus.SHORTENED, metresAtEnd = 3470)
        assertEquals(false, FinishedRule.finished(row))
    }

    @Test
    fun `case 18 v25 time end reached with no distance known`() {
        val row = result(time(900), secondsAtEnd = 900)
        assertEquals(true, FinishedRule.finished(row))
        assertNull(FinishedRule.reading(row))
        assertNull(FinishedRule.reading(row.copy(metres = 3500, distanceSource = DistanceSource.MANUAL)))
        assertNull(FinishedRule.reading(result(distance(2414), metresAtEnd = 2414)))
    }

    @Test
    fun `at-end readings must not be negative`() {
        assertFailsWith<IllegalArgumentException> { result(time(900), metresAtEnd = -1) }
        assertFailsWith<IllegalArgumentException> { result(time(900), secondsAtEnd = -1) }
    }
}
