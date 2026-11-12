package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ReadingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardioEditTest {
    private val row = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(1800)),
        CardioSetStatus.FINISHED, 1900, 5700, DistanceSource.GPS, 5400, 1800, ReadingSource.GPS)
    private val exact = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(300)),
        CardioSetStatus.FINISHED, 300, 888, DistanceSource.GPS, secondsAtEnd = 300)

    @Test fun `a seconds only edit keeps GPS total and reading`() {
        assertEquals(row.copy(elapsedSeconds = 1950), CardioEdit.apply(row, 1950, 5700, null))
    }

    @Test fun `a distance edit becomes manual and keeps the GPS reading`() {
        assertEquals(row.copy(metres = 5600, distanceSource = DistanceSource.MANUAL), CardioEdit.apply(row, 1900, 5600, null))
    }

    @Test fun `a supplied reading replaces GPS with typed`() {
        assertEquals(row.copy(metresAtEnd = 5300, readingSource = ReadingSource.TYPED), CardioEdit.apply(row, 1900, 5700, 5300))
    }

    @Test fun `a reading above the total is invalid`() {
        assertNull(CardioEdit.apply(row, 1900, 5700, 5800))
    }

    @Test fun `unchanged totals keep their source and supplied reading`() {
        assertEquals(row, CardioEdit.apply(row, 1900, 5700, null))
        for (source in DistanceSource.entries) {
            val previous = row.copy(distanceSource = source)
            assertEquals(previous.copy(elapsedSeconds = 1950), CardioEdit.apply(previous, 1950, 5700, null))
        }
    }

    @Test fun `an exact GPS finish edited to a new duration keeps its reading unknown`() {
        assertEquals(exact.copy(spec = exact.spec.copy(end = Measure.Time(301)), elapsedSeconds = 301, secondsAtEnd = 301),
            CardioEdit.apply(exact, 301, 888, null))
    }

    @Test fun `an exact GPS finish accepts a typed reading`() {
        assertEquals(exact.copy(metresAtEnd = 805, readingSource = ReadingSource.TYPED), CardioEdit.apply(exact, 300, 888, 805))
    }
}
