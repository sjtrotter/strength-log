package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.progression.FinishedRule
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.SprintAttempt

object GoalProgress {
    /**
     * Fastest continuous attempt in seconds, or null. Activity must match and the session must
     * have exactly one WORK set; warm-up and cool-down are fine, intervals never count.
     * That set must be FINISHED with positive elapsed time and cover at least the goal distance,
     * using recorded metres first, then a planned Distance end. Longer attempts use their whole
     * time: reaching the goal distance was no slower than completing the longer attempt.
     * A row with at-end readings uses them: the time at the planned end, never the overrun.
     * A timed sprint rep counts only in its own series ([SprintComparison]).
     */
    fun bestTimed(goal: PlanGoal.Timed, sessions: List<Pair<CardioActivity?, List<CardioSetResult>>>): Int? =
        sessions.mapNotNull { (activity, results) ->
            if (activity != goal.activity) return@mapNotNull null
            val work = results.filter { it.spec.role == CardioRole.WORK }.singleOrNull()
                ?: return@mapNotNull null
            if (work.timing != null) return@mapNotNull null
            val atEnd = FinishedRule.finished(work)
            val metres = (if (atEnd != null) work.metresAtEnd else work.metres)
                ?: (work.spec.end as? Measure.Distance)?.metres ?: return@mapNotNull null
            val seconds = (if (atEnd != null) work.secondsAtEnd else work.elapsedSeconds) ?: return@mapNotNull null
            seconds.takeIf { (atEnd ?: (work.status == CardioSetStatus.FINISHED)) && it > 0 && metres >= goal.metres }
        }.minOrNull()

    fun met(goal: PlanGoal.Timed, best: Int?): Boolean = best != null && best < goal.underSeconds
    fun bestShuttles(goal: PlanGoal.Shuttle): Int? = goal.attempts.maxOfOrNull { it.shuttles }
    fun met(goal: PlanGoal.Shuttle, best: Int?): Boolean = best != null && best >= goal.targetShuttles

    /** The best attempt in the series of the newest one; a hand time within 0.1 s of the standing best leaves it
     *  (design §3.1.3). */
    fun bestSprint(goal: PlanGoal.Sprint): SprintAttempt? {
        val ordered = goal.attempts.sortedBy { it.completedAt }
        val newest = ordered.lastOrNull() ?: return null
        var best: SprintAttempt? = null
        for (attempt in ordered) {
            if (attempt.time.timingMethod != newest.time.timingMethod ||
                attempt.time.startProtocol != newest.time.startProtocol) continue
            val standing = best
            if (standing == null || SprintComparison.verdict(attempt.time, standing.time) == SprintVerdict.FASTER) {
                best = attempt
            }
        }
        return best
    }
}
