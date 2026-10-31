package cloud.trotter.log.strength.domain.model

/** Set count belongs to [ProgramExercise.targetSets], independently of the target. */
data class Prescription(
    val target: PrescribedTarget,
    val eachSide: Boolean = false,
    /** A band or other resistance described in words, e.g. "red band". */
    val resistance: String = "",
)

sealed interface PrescribedTarget {
    data class LoadReps(val lb: Double, val reps: Int) : PrescribedTarget
    data class Reps(val reps: Int) : PrescribedTarget
    data class Hold(val seconds: Int, val addedLb: Double = 0.0) : PrescribedTarget
    /** Reps counted within a time window.
     *  The window lives in the prescription; the log holds the count. */
    data class RepsInTime(val seconds: Int, val repAim: Int? = null) : PrescribedTarget
}
