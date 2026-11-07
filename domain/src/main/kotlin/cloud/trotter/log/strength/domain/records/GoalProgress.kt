package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal

object GoalProgress {
    /**
     * Fastest continuous attempt in seconds, or null. Activity must match and the session must
     * have exactly one WORK set; warm-up and cool-down are fine, intervals never count.
     * That set must be FINISHED with positive elapsed time and cover at least the goal distance,
     * using recorded metres first, then a planned Distance end. Longer attempts use their whole
     * time: reaching the goal distance was no slower than completing the longer attempt.
     */
    fun bestTimed(goal: PlanGoal.Timed, sessions: List<Pair<CardioActivity?, List<CardioSetResult>>>): Int? =
        sessions.mapNotNull { (activity, results) ->
            if (activity != goal.activity) return@mapNotNull null
            val work = results.filter { it.spec.role == CardioRole.WORK }.singleOrNull()
                ?: return@mapNotNull null
            val metres = work.metres ?: (work.spec.end as? Measure.Distance)?.metres ?: return@mapNotNull null
            work.elapsedSeconds.takeIf {
                work.status == CardioSetStatus.FINISHED && it > 0 && metres >= goal.metres
            }
        }.minOrNull()

    fun met(goal: PlanGoal.Timed, best: Int?): Boolean = best != null && best < goal.underSeconds
    fun bestShuttles(goal: PlanGoal.Shuttle): Int? = goal.attempts.maxOfOrNull { it.shuttles }
    fun met(goal: PlanGoal.Shuttle, best: Int?): Boolean = best != null && best >= goal.targetShuttles
}
