package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals

class CardioPresentationTest {
    @Test
    fun `planned detail sums timed ends including warmup and recovery regardless of aims`() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
            CardioSetSpec(CardioRole.WORK, Measure.Time(120), Measure.Distance(400), hard = true),
            CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)),
        ))
        assertEquals("9:00", block.plannedTimeDetail())
    }

    @Test
    fun `a distance end leaves planned detail empty even with a timed aim`() {
        val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(
            CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
        ))
        assertEquals("", block.plannedTimeDetail())
    }
}
