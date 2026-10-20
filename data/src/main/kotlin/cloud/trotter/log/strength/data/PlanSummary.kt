package cloud.trotter.log.strength.data

/** A plan and its rotation and history labels for the plan picker. */
data class PlanSummary(
    val id: Long,
    val name: String,
    val nextDayId: String?,
    val nextDayTitle: String?,
    val selected: Boolean,
    val archived: Boolean,
    val lastSessionAt: Long?,
)
