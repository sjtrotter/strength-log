package cloud.trotter.log.strength.data.prefs

import cloud.trotter.log.strength.data.serialization.SetDto
import kotlinx.serialization.Serializable

@Serializable
data class WorkoutRecord(
    val runId: String,
    val selectedKey: String? = null,
    val undo: UndoTarget? = null,
    val review: ReviewTarget? = null,
)

@Serializable
data class UndoTarget(
    val programExerciseId: Long,
    val slot: String,
    val index: Int,
    val before: SetDto,
    val after: SetDto,
    val loggedAtMillis: Long,
    val partner: UndoHalf? = null,
)

@Serializable
data class UndoHalf(val before: SetDto, val after: SetDto)

/** The logged row the set screen still shows; loggedAtMillis is the completing log's stamp. */
@Serializable
data class ReviewTarget(val programExerciseId: Long, val index: Int, val loggedAtMillis: Long)
