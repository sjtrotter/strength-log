package cloud.trotter.log.strength.domain.units

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import kotlin.test.Test
import kotlin.test.assertEquals

class BodyweightTest {
    @Test fun `metric and pound inputs round trip at tenths precision`() {
        assertEquals(95.5, Bodyweight.display(Bodyweight.fromDisplay(95.5, WeightUnit.KG), WeightUnit.KG))
        assertEquals(210.5, Bodyweight.display(Bodyweight.fromDisplay(210.5, WeightUnit.LB), WeightUnit.LB))
    }

    @Test fun `a tenth of a pound leaves pinned goals seeds and cascades unchanged`() {
        val base = LifterConfig()
        val squat = ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)
        fun seeds(config: LifterConfig) = SetSeeder.seed(squat, GoalCalculator.goalFor(squat, config), config)
        for (bodyweight in listOf(234.9, 235.1)) {
            val config = base.copy(bodyweightLb = bodyweight)
            for (id in listOf("bb_back_squat", "bb_bench", "trap_dl", "incline_db", "rdl", "leg_press", "cs_row", "db_bench")) {
                val entry = ExerciseLibrary.get(id)
                assertEquals(GoalCalculator.goalFor(entry, base), GoalCalculator.goalFor(entry, config), id)
            }
            assertEquals(seeds(base), seeds(config))
            val top = seeds(base).indexOfFirst { it.kind == SetKind.TOP }
            assertEquals(SetEditor.editWeight(seeds(base), top, 245.0), SetEditor.editWeight(seeds(config), top, 245.0))
        }
    }
}
