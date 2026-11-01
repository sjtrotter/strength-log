package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SupersetPartner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProgramSlotForRunTest {
    @Test fun `only dropped ids lose their partner and the plan stays intact`() {
        val pair = ProgramExercise("bb_bench", superset = SupersetPartner("ez_curl"))
        val slots = listOf(ProgramSlot(1, 0, pair), ProgramSlot(2, 1, pair))
        val resolved = slots.forRun(setOf(1))
        assertNull(resolved[0].exercise.superset)
        assertEquals(slots[0].exercise.copy(superset = null), resolved[0].exercise)
        assertSame(slots[1], resolved[1])
        assertEquals(pair, slots[0].exercise)
        assertEquals(slots, slots.forRun(emptySet()))
    }
}
