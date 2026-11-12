package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.GpsSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.records.CardioBests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinishedRuleMeasuredTest {
    private val timeEnd = CardioSetSpec(CardioRole.WORK, Measure.Time(1500), Measure.Distance(5000))
    private val distanceEnd = CardioSetSpec(CardioRole.WORK, Measure.Distance(2414), Measure.Time(960))
    private fun result(
        spec: CardioSetSpec = timeEnd,
        status: CardioSetStatus = CardioSetStatus.FINISHED,
        elapsed: Int = 1690,
        metres: Int? = null,
    ) = CardioSetResult(spec, status, elapsed, metres, metres?.let { DistanceSource.MANUAL })

    @Test fun `a measured time end keeps the total and its GPS reading`() {
        val row = FinishedRule.measured(result(), GpsSet(5600, 5050, true))
        assertEquals(result(metres = 5600).copy(distanceSource = DistanceSource.GPS,
            metresAtEnd = 5050, secondsAtEnd = 1500, readingSource = ReadingSource.GPS), row)
        assertFalse(FinishedRule.awaitsReading(row))
        assertEquals(5050, FinishedRule.reading(row))
        assertTrue(CardioBests.counts(row))
    }

    @Test fun `a measured overrun without a reading asks for one without deriving it`() {
        val row = FinishedRule.measured(result(), GpsSet(5600, null, true))
        assertEquals(5600, row.metres)
        assertEquals(DistanceSource.GPS, row.distanceSource)
        assertEquals(1500, row.secondsAtEnd)
        assertNull(row.metresAtEnd)
        assertNull(row.readingSource)
        assertTrue(FinishedRule.awaitsReading(row))
        assertNull(FinishedRule.reading(row))
        assertEquals(ReadingSource.TYPED, assertNotNull(FinishedRule.withReading(row, 5050)).readingSource)
    }

    @Test fun `an exact time end can have either a GPS reading or an unknown reading`() {
        val exact = result(elapsed = 1500)
        val measured = FinishedRule.measured(exact, GpsSet(5050, 5050, true))
        assertEquals(5050 to 1500, measured.metresAtEnd to measured.secondsAtEnd)
        assertEquals(ReadingSource.GPS, measured.readingSource)
        val unknown = FinishedRule.measured(exact, GpsSet(5050, null, true))
        assertEquals(5050, unknown.metres)
        assertEquals(DistanceSource.GPS, unknown.distanceSource)
        assertEquals(1500, unknown.secondsAtEnd)
        assertNull(unknown.metresAtEnd)
        assertNull(unknown.readingSource)
        assertFalse(FinishedRule.awaitsReading(unknown))
        assertNull(FinishedRule.reading(unknown))
    }

    @Test fun `typed skipped absent and partial measurements leave the result unchanged`() {
        val gps = GpsSet(5600, 5050, true)
        val typed = result(metres = 5400)
        assertEquals(typed, FinishedRule.measured(typed, gps))
        val skipped = result(status = CardioSetStatus.SKIPPED, elapsed = 0)
        assertEquals(skipped, FinishedRule.measured(skipped, gps))
        assertEquals(result(), FinishedRule.measured(result(), null))
        assertEquals(result(), FinishedRule.measured(result(), GpsSet(5600, null, false)))
    }

    @Test fun `a shortened time end gets only its GPS total`() {
        val row = FinishedRule.measured(result(status = CardioSetStatus.SHORTENED, elapsed = 1000), GpsSet(3300, null, true))
        assertEquals(3300, row.metres)
        assertEquals(DistanceSource.GPS, row.distanceSource)
        assertNull(row.metresAtEnd)
        assertNull(row.secondsAtEnd)
        assertNull(row.readingSource)
    }

    @Test fun `a distance end needs more than twenty metres of overrun to replace the tap`() {
        val stopped = result(distanceEnd, CardioSetStatus.SHORTENED, 1000)
        val passed = FinishedRule.measured(stopped, GpsSet(2736, 900, true))
        assertEquals(stopped.copy(status = CardioSetStatus.FINISHED, metres = 2736, distanceSource = DistanceSource.GPS,
            metresAtEnd = 2414, secondsAtEnd = 900, readingSource = ReadingSource.GPS), passed)
        val near = FinishedRule.measured(stopped, GpsSet(2430, 995, true))
        assertEquals(stopped.copy(metres = 2430, distanceSource = DistanceSource.GPS), near)
        assertFalse(FinishedRule.gpsPassed(Measure.Distance(2414), GpsSet(2434, 990, true)))
        assertTrue(FinishedRule.gpsPassed(Measure.Distance(2414), GpsSet(2435, 990, true)))
    }

    @Test fun `editing the total keeps a GPS reading`() {
        val row = FinishedRule.measured(result(), GpsSet(5600, 5050, true))
        assertEquals(row.copy(metres = 5500, distanceSource = DistanceSource.MANUAL),
            FinishedRule.afterEdit(row, row.copy(metres = 5500, distanceSource = DistanceSource.MANUAL)))
    }

    @Test fun `typed readings replace GPS readings only within reached end totals`() {
        val row = FinishedRule.measured(result(), GpsSet(5600, 5050, true))
        assertEquals(row.copy(metresAtEnd = 5300, readingSource = ReadingSource.TYPED), FinishedRule.typed(row, 5300))
        assertNull(FinishedRule.typed(row, 5601))
        val shortened = FinishedRule.measured(result(status = CardioSetStatus.SHORTENED, elapsed = 1000), GpsSet(3300, null, true))
        assertNull(FinishedRule.typed(shortened, 3200))
        val distance = FinishedRule.measured(result(distanceEnd, CardioSetStatus.SHORTENED, 1000), GpsSet(2736, 900, true))
        assertEquals(distance.copy(secondsAtEnd = 950, readingSource = ReadingSource.TYPED), FinishedRule.typed(distance, 950))
        assertNull(FinishedRule.typed(distance, 1001))
    }
}
