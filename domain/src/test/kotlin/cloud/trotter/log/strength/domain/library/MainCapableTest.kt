package cloud.trotter.log.strength.domain.library

import kotlin.test.Test
import kotlin.test.assertEquals

class MainCapableTest {
    @Test fun `main-capable entries are exactly the eight standard lifts`() {
        val ids = ExerciseLibrary.entries.filter { it.mainCapable }.map { it.id }.toSet()
        assertEquals(setOf("bb_back_squat", "trap_dl", "conv_dl", "sumo_dl", "bb_bench", "incline_db", "ohp", "bb_row"), ids)
        assertEquals(ExerciseLibrary.entries.filter { it.goal is GoalSource.Std }.map { it.id }.toSet(), ids)
    }
}
