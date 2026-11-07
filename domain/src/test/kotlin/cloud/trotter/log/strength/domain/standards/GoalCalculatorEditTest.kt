package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SlotSeeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GoalCalculatorEditTest {
    private val squat = ExerciseLibrary.get("bb_back_squat")
    private val cfg = LifterConfig(goalsLb = mapOf(squat.id to 200.0))

    @Test
    fun `an edited goal replaces only that lift's goal`() {
        assertEquals(200.0, GoalCalculator.goalFor(squat, cfg))
        assertEquals(GoalTarget.Weight(200.0, false), GoalCalculator.targetFor(squat, cfg))
        assertEquals(195.0, GoalCalculator.goalFor(ExerciseLibrary.get("bb_bench"), cfg))
        assertEquals(170.0, GoalCalculator.goalFor(ExerciseLibrary.get("rdl"), cfg))
    }

    @Test
    fun `seeding an unseeded slot reads the edited goal`() {
        val rows = assertNotNull(SlotSeeds.main(
            ProgramExercise(squat.id, SetStructure.RAMP_TOP_BACKOFF, 6), squat, cfg,
        ))
        assertEquals(200.0, rows.single { it.kind == SetKind.TOP }.weightLb)
    }
}
