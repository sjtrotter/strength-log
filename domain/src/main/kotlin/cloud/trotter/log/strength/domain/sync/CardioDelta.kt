package cloud.trotter.log.strength.domain.sync

import kotlinx.serialization.Serializable

/** One completed wrist cardio session. [stamp] is its durable event identity. */
@Serializable
data class CardioDelta(
    val schemaVersion: Int = 1,
    val dayId: String,
    val mode: String,
    val hard: Boolean,
    val label: String,
    val startedAt: Long,
    val completedAt: Long,
    val seconds: Int,
    val stepsCompleted: Int,
    val stamp: Long,
)
