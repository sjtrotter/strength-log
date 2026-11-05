package cloud.trotter.log.strength.domain.units

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import kotlin.test.Test
import kotlin.test.assertEquals

class BodyweightTest {
    private val configs = listOf(LifterConfig(), LifterConfig(bodyweightLb = 235.0, age = 40))

    private fun seedMain(id: String, config: LifterConfig): List<LoggedSet> {
        val exercise = ProgramExercise(id, structure = SetStructure.RAMP_TOP_BACKOFF)
        return SetSeeder.seed(exercise, GoalCalculator.goalFor(exercise, config), config)
    }

    private fun row(set: LoggedSet) = Triple(set.weightLb, set.reps, set.kind)

    @Test fun `metric and pound inputs round trip at tenths precision`() {
        assertEquals(95.5, Bodyweight.display(Bodyweight.fromDisplay(95.5, WeightUnit.KG), WeightUnit.KG))
        assertEquals(210.5, Bodyweight.display(Bodyweight.fromDisplay(210.5, WeightUnit.LB), WeightUnit.LB))
    }

    @Test fun `default and explicit 235 pound configs reproduce pinned goals`() {
        val goals = listOf(
            "bb_back_squat" to 235.0,
            "bb_bench" to 195.0,
            "trap_dl" to 255.0,
            "incline_db" to 75.0,
            "rdl" to 170.0,
            "leg_press" to 330.0,
            "cs_row" to 155.0,
            "db_bench" to 80.0,
        )
        for (config in configs) {
            for ((id, goal) in goals) {
                assertEquals(goal, GoalCalculator.goalFor(ExerciseLibrary.get(id), config), 1e-9, "$id: $config")
            }
        }
    }

    @Test fun `default and explicit 235 pound configs reproduce pinned seeded sequences`() {
        for (config in configs) {
            assertEquals(
                listOf(
                    Triple(130.0, 5, SetKind.RAMP),
                    Triple(165.0, 5, SetKind.RAMP),
                    Triple(190.0, 5, SetKind.RAMP),
                    Triple(210.0, 3, SetKind.RAMP),
                    Triple(235.0, 5, SetKind.TOP),
                    Triple(175.0, 8, SetKind.BACKOFF),
                ),
                seedMain("bb_back_squat", config).map(::row),
                config.toString(),
            )
            assertEquals(
                listOf(
                    Triple(105.0, 5, SetKind.RAMP),
                    Triple(135.0, 5, SetKind.RAMP),
                    Triple(155.0, 5, SetKind.RAMP),
                    Triple(175.0, 3, SetKind.RAMP),
                    Triple(195.0, 5, SetKind.TOP),
                    Triple(145.0, 8, SetKind.BACKOFF),
                ),
                seedMain("bb_bench", config).map(::row),
                config.toString(),
            )
        }
    }

    @Test fun `default and explicit 235 pound configs reproduce pinned squat cascade`() {
        for (config in configs) {
            val seeded = seedMain("bb_back_squat", config)
            val topIndex = seeded.indexOfFirst { it.kind == SetKind.TOP }
            val cascaded = SetEditor.editWeight(seeded, topIndex, 245.0)
            assertEquals(
                listOf(
                    Triple(135.0, 5, SetKind.RAMP),
                    Triple(170.0, 5, SetKind.RAMP),
                    Triple(195.0, 5, SetKind.RAMP),
                    Triple(220.0, 3, SetKind.RAMP),
                    Triple(245.0, 5, SetKind.TOP),
                    Triple(185.0, 8, SetKind.BACKOFF),
                ),
                cascaded.map(::row),
                config.toString(),
            )
        }
    }
}
