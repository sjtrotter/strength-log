package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.planner.FitnessTest

/** Stored by name in derived event ids; constants must never be renamed. */
enum class TestFormat(val setType: TestSetType?) {
    REPS_IN_WINDOW(TestSetType.REPS_IN_WINDOW),
    REPS_CONTINUOUS(TestSetType.REPS_CONTINUOUS),
    MAX_HOLD(TestSetType.MAX_HOLD),
    HEAVIEST_FOR_REPS(null);

    companion object {
        fun eligible(entry: ExerciseEntry): List<TestFormat> = when (val goal = entry.goal) {
            is GoalSource.Reps -> if (entry.pattern == MovementPattern.PLYOMETRIC) emptyList()
                else listOf(REPS_IN_WINDOW, REPS_CONTINUOUS)
            is GoalSource.Time -> if (goal.addedWeightLb == 0.0) listOf(MAX_HOLD) else emptyList()
            GoalSource.Weighted -> if (entry.mainCapable) listOf(HEAVIEST_FOR_REPS) else emptyList()
        }
    }
}

sealed interface TestPick {
    data class Library(val entry: ExerciseEntry) : TestPick
    data class Run(val test: FitnessTest) : TestPick
}
