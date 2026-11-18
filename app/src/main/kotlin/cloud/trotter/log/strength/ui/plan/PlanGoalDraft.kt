package cloud.trotter.log.strength.ui.plan

import android.os.Parcelable
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import kotlinx.parcelize.Parcelize

/** [PlanGoalDraft.underSeconds] while A time is chosen and its field holds no time above zero; 0 is Just finish. */
internal const val INVALID_TIME = -1

@Parcelize
data class PlanGoalDraft(
    val timed: Boolean = true,
    val activity: String = CardioActivity.ROAD_RUN.name,
    val metres: Int = 3219,
    val underSeconds: Int = 960,
    val protocol: String = "",
    val targetShuttles: Int = 50,
    val byEpochDay: Long? = null,
) : Parcelable {
    fun goal(): PlanGoal? = runCatching {
        require(PlanGoalDate.isValid(byEpochDay))
        require(underSeconds != INVALID_TIME)
        if (timed) PlanGoal.Timed(CardioActivity.valueOf(activity), metres, underSeconds.takeUnless { it == 0 }, byEpochDay)
        else PlanGoal.Shuttle(protocol.trim(), targetShuttles, byEpochDay = byEpochDay)
    }.getOrNull()

    companion object {
        fun from(goal: PlanGoal?): PlanGoalDraft = when (goal) {
            is PlanGoal.Timed -> PlanGoalDraft(activity = goal.activity.name, metres = goal.metres,
                underSeconds = goal.underSeconds ?: 0, byEpochDay = goal.byEpochDay)
            is PlanGoal.Shuttle -> PlanGoalDraft(timed = false, protocol = goal.protocol,
                targetShuttles = goal.targetShuttles, byEpochDay = goal.byEpochDay)
            is PlanGoal.Sprint, null -> PlanGoalDraft()
        }
    }
}
