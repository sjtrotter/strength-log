package cloud.trotter.log.strength.domain.model

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SetStructureTest {
    private val squat = ExerciseLibrary.get("bb_back_squat")

    @Test
    fun `weighted standard entries default to ramp top backoff`() {
        assertEquals(SetStructure.RAMP_TOP_BACKOFF, SetStructure.defaultFor(squat))
    }

    @Test
    fun `non-standard and non-weighted entries default to straight`() {
        val goals = listOf(
            GoalSource.FracOfStd(0.72, StandardLift.SQUAT),
            GoalSource.Flat(60.0),
            GoalSource.Reps(12),
            GoalSource.Time(45, 25.0),
        )
        for (goal in goals) {
            assertEquals(SetStructure.STRAIGHT, SetStructure.defaultFor(squat.copy(goal = goal)))
        }
    }
}
