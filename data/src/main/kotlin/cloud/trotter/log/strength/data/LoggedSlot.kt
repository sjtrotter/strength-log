package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.model.LoggedSet

data class LoggedSlot(
    val programExerciseId: Long,
    val slot: String,
    val sets: List<LoggedSet>,
    val exerciseId: String? = null,
)
