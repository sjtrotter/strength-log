package cloud.trotter.log.strength.domain.seeding

/** Null leaves a value alone; unticking clears both timing stamps. */
data class SetEdit(
    val weightLb: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val done: Boolean? = null,
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val watchEditedAt: Long? = null,
)
