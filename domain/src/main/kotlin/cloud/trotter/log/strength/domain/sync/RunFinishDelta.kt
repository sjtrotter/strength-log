package cloud.trotter.log.strength.domain.sync

import kotlinx.serialization.Serializable

/**
 * The step 6 offline Finish from the watch. Defined so the shape is fixed; no phone
 * handler exists yet, and an unknown path is dropped.
 */
@Serializable
data class RunFinishDelta(
    val schemaVersion: Int = 1,
    val runId: String,
    val planId: Long,
    val dayId: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val editedAtMillis: Long,
    val tracks: List<RunFinishTrack> = emptyList(),
)

@Serializable
data class RunFinishTrack(
    val programExerciseId: Long,
    val slot: String,
    val exerciseId: String,
    val sets: List<RunFinishSet> = emptyList(),
)

@Serializable
data class RunFinishSet(
    val weightLb: Double,
    val reps: Int,
    val seconds: Int = 0,
    val done: Boolean,
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val watchEditedAt: Long? = null,
)
