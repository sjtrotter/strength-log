package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ManualCardioTest {
    @Test fun `a manual result is one finished timed work set`() {
        val result = ManualCardio.result(2412, 7100)
        assertEquals(CardioRole.WORK, result.spec.role)
        assertEquals(Measure.Time(2412), result.spec.end)
        assertEquals(CardioSetStatus.FINISHED, result.status)
        assertEquals(2412, result.elapsedSeconds)
        assertEquals(7100, result.metres)
        assertEquals(DistanceSource.MANUAL, result.distanceSource)
        val withoutDistance = ManualCardio.result(1800, null)
        assertNull(withoutDistance.metres)
        assertNull(withoutDistance.distanceSource)
        val summary = ManualCardio.summary(CardioActivity.ROAD_RUN, 2412)
        assertEquals(CardioBlocks.legacyModeOf(CardioActivity.ROAD_RUN), summary.mode)
        assertEquals("Road run", summary.label)
        assertEquals(1, summary.stepsCompleted)
        assertEquals(2412, summary.seconds)
        assertFalse(summary.hard)
    }
}
