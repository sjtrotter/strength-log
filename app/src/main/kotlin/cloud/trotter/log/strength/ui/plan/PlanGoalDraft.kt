package cloud.trotter.log.strength.ui.plan

import android.os.Parcelable
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.GoalTime
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
        if (timed) {
            val cardioActivity = CardioActivity.valueOf(activity)
            require(underSeconds == 0 || underSeconds >= GoalTime.floorSeconds(metres, cardioActivity))
            PlanGoal.Timed(cardioActivity, metres, underSeconds.takeUnless { it == 0 }, byEpochDay)
        } else PlanGoal.Shuttle(protocol.trim(), targetShuttles, byEpochDay = byEpochDay)
    }.getOrNull()

    internal fun invalidateTimeBelowFloor(): PlanGoalDraft {
        val cardioActivity = CardioActivity.entries.firstOrNull { it.name == activity } ?: return this
        return if (timed && underSeconds != 0 && underSeconds < GoalTime.floorSeconds(metres, cardioActivity)) {
            copy(underSeconds = INVALID_TIME)
        } else this
    }

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

@Parcelize
data class SprintGoalDraft(val targetText: String = "", val byEpochDay: Long? = null) : Parcelable
