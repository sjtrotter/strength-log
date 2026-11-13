package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.RunWalkLadder

/** The readiness phases (design §3.1.2), in order. [minWeeks] feeds DATE_TOO_CLOSE (Q4). Stored nowhere. */
enum class RunPhase(val minWeeks: Int) { LADDER(8), BASE(6), HALF_BUILD(8), MARATHON_BUILD(8), ULTRA_BUILD(8) }

/** §3.1.2 gates: "30 min continuous" (or 5,000 m, Q4), "10 km or 60 min", "21 km or 2 h", and
 *  "30 km or 3 h, or a logged marathon". Week forms need the log in PR 4b. */
internal fun RunPhase.met(anchor: RecentMaxima, marathonBest: Boolean): Boolean = when (this) {
    RunPhase.LADDER -> (anchor.seconds ?: 0) >= 1_800 || (anchor.metres ?: 0) >= 5_000
    RunPhase.BASE -> (anchor.metres ?: 0) >= 10_000 || (anchor.seconds ?: 0) >= 3_600
    RunPhase.HALF_BUILD -> (anchor.metres ?: 0) >= 21_000 || (anchor.seconds ?: 0) >= 7_200
    RunPhase.MARATHON_BUILD -> (anchor.metres ?: 0) >= 30_000 || (anchor.seconds ?: 0) >= 10_800 || marathonBest
    RunPhase.ULTRA_BUILD -> false
}

internal fun startPhase(run: RunAnswers, anchor: RecentMaxima?): RunPhase? {
    val phases = run.goal.phases
    if (phases.isEmpty()) return null
    if (anchor == null) return if (run.experience == RunExperience.NEW) RunPhase.LADDER else RunPhase.BASE
    return phases.firstOrNull { !it.met(anchor, run.marathonBestSeconds != null) } ?: phases.last()
}

internal fun weeksNeeded(goal: RunGoal, start: RunPhase): Int =
    goal.phases.filter { it >= start && it <= requireNotNull(goal.eventPhase) }.sumOf { it.minWeeks }

internal fun ladderStart(anchor: RecentMaxima?, injury: Boolean): Int {
    val seconds = anchor?.seconds ?: 0
    val level = (RunWalkLadder.LEVELS.indexOfLast { it.runSeconds <= seconds } + 1).coerceAtLeast(1)
    return (level - if (injury) 1 else 0).coerceAtLeast(1)
}
