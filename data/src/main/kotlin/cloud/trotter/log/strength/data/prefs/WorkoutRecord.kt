package cloud.trotter.log.strength.data.prefs

import cloud.trotter.log.strength.data.serialization.SetDto
import kotlinx.serialization.Serializable

@Serializable
data class WorkoutRecord(
    val runId: String,
    val selectedKey: String? = null,
    val undo: UndoTarget? = null,
)

@Serializable
data class UndoTarget(
    val programExerciseId: Long,
    val slot: String,
    val index: Int,
    val before: SetDto,
    val after: SetDto,
    val loggedAtMillis: Long,
    val restStarted: Boolean,
)
