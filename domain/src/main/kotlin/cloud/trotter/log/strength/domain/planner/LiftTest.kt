package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary

/** The required weight lives only in plan settings. */
data class LiftTest(val exerciseId: String, val reps: Int) {
    init {
        require(ExerciseLibrary.find(exerciseId)?.mainCapable == true)
        require(reps in 1..10)
    }
}
