package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.cardio.Clocks
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.GpsSet
import cloud.trotter.log.strength.domain.model.ReadingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioRunGpsTest {
    private fun run(metres: Int) = requireNotNull(runningCardioFixture().run).copy(
        results = listOf(SavedCardioResult("FINISHED", 300, metres)), wallStart = null)
    private fun stamp(run: CardioRun, typed: Boolean = false) = CardioGpsSet(run.runId, 0, 900, 900, 300, true, typed)

    @Test fun `a complete untyped stamp restores GPS total and reading`() {
        val run = run(900)
        val row = run.resultSets(listOf(stamp(run))).single()
        assertEquals(DistanceSource.GPS, row.distanceSource)
        assertEquals(ReadingSource.GPS, row.readingSource)
        assertEquals(900 to 300, row.metresAtEnd to row.secondsAtEnd)
    }
    @Test fun `an explicitly typed equal total remains manual and keeps the reading`() {
        val run = run(900)
        val row = run.resultSets(listOf(stamp(run, true))).single()
        assertEquals(DistanceSource.MANUAL, row.distanceSource)
        assertEquals(ReadingSource.GPS, row.readingSource)
    }
    @Test fun `a typed total below the GPS reading drops that reading`() {
        val run = run(850)
        val row = run.resultSets(listOf(stamp(run, true))).single()
        assertEquals(850, row.metres)
        assertEquals(DistanceSource.MANUAL, row.distanceSource)
        assertNull(row.metresAtEnd)
        assertNull(row.readingSource)
    }
    @Test fun `a partial stamp keeps manual mapping and prefills its draft`() {
        val run = requireNotNull(runningCardioFixture().run)
        val (next, stamps) = run.stepped {
            it.finishStep(CardioSetStatus.SHORTENED, 200, Clocks(201_000, 201_000, 1), gps = GpsSet(540, null, false))
        }
        assertEquals("540", next.drafts.single().text)
        assertEquals(CardioGpsSet(run.runId, 0, 540, null, null, false, false), stamps.single())
        assertEquals(next.resultSets, next.resultSets(stamps))
    }
    @Test fun `a typed live total is stamped as typed and never prefilled from GPS`() {
        val run = requireNotNull(runningCardioFixture().run)
        val (next, stamps) = run.stepped {
            it.finishStep(CardioSetStatus.FINISHED, 400, Clocks(401_000, 401_000, 1),
                metres = 500, gps = GpsSet(540, null, false))
        }
        assertEquals(true, stamps.single().typed)
        assertEquals("", next.drafts.single().text)
        assertEquals(500, next.results.single().metres)
    }

}
