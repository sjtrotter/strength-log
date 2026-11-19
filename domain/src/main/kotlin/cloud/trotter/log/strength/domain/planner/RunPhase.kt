package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.RunWalkLadder

/** The readiness phases (design §3.1.2), in order. [minWeeks] feeds DATE_TOO_CLOSE (Q4); the ladder's length is
 *  derived from the plan ([ladderWeeks]). Stored as recipe v2's `runPhase`; a re-plan advances it
 *  (design revision 48). */
enum class RunPhase(val minWeeks: Int?) { LADDER(null), BASE(6), HALF_BUILD(8), MARATHON_BUILD(8), ULTRA_BUILD(8) }

/** §3.1.2 gates: "30 min continuous" (or 5,000 m, Q4), "10 km or 60 min", "21 km or 2 h, or a 30 km or 3 h week",
 *  and "30 km or 3 h, or a logged marathon". */
internal fun RunPhase.met(anchor: RecentMaxima, marathonBest: Boolean, week: RecentMaxima? = null,
                          running: Int? = null, runningMetres: Int? = null): Boolean = when (this) {
    RunPhase.LADDER -> ladderMet(anchor, running, runningMetres)
    RunPhase.BASE -> (anchor.metres ?: 0) >= 10_000 || (anchor.seconds ?: 0) >= 3_600
    RunPhase.HALF_BUILD -> (anchor.metres ?: 0) >= 21_000 || (anchor.seconds ?: 0) >= 7_200 ||
        (week?.metres ?: 0) >= 30_000 || (week?.seconds ?: 0) >= 10_800
    RunPhase.MARATHON_BUILD -> (anchor.metres ?: 0) >= 30_000 || (anchor.seconds ?: 0) >= 10_800 || marathonBest
    RunPhase.ULTRA_BUILD -> false
}

/** The ladder gate reads WORK only (revision 47); without a log, [anchor] is the typed longest. */
internal fun ladderMet(anchor: RecentMaxima?, runningSeconds: Int?, runningMetres: Int?): Boolean =
    (runningSeconds ?: anchor?.seconds ?: 0) >= 1_800 || (runningMetres ?: anchor?.metres ?: 0) >= 5_000

/** Practice: 4:00 per km keeps a typed-distance start at or below most New or Some runners' running time.
 *  The ladder corrects upward at two Easy runs a level. The planning estimate ESTIMATE_SECONDS_PER_METRE
 *  (0.36 s/m) is slow so sessions fit their minutes; it would overplace runners faster than 6:00 per km. */
internal const val TYPED_START_SECONDS_PER_METRE = 0.24

/** Whether [run]'s family has a finished session in the window; the log then wins over the typed longest. */
private fun hasLog(run: RunAnswers, known: KnownLoads): Boolean = CardioFamily.of(run.activity) in known.longestRecent

/** The longest WORK metres in the window when the family has a finished log, else the typed longest. */
internal fun runningMetres(run: RunAnswers, known: KnownLoads): Int? {
    val family = CardioFamily.of(run.activity)
    return if (hasLog(run, known)) known.longestRunningMetres[family] else run.seed()?.metres
}

internal fun startPhase(run: RunAnswers, anchor: RecentMaxima?, week: RecentMaxima?, running: Int?, runningMetres: Int?,
                        atLeast: RunPhase? = null): RunPhase? {
    val path = run.phases
    if (path.isEmpty()) return null
    val ladderOnly = run.goal.phases.isEmpty()
    // On a ladder-only path a BASE floor means the ladder is done; the goal's own plan has no phase.
    if (ladderOnly && atLeast != null) return null
    val start = if (anchor == null) {
        if (run.experience == RunExperience.NEW) RunPhase.LADDER else path.firstOrNull { it != RunPhase.LADDER }
    } else {
        path.firstOrNull { !it.met(anchor, run.marathonBestSeconds != null, week, running, runningMetres) }
            ?: path.last().takeUnless { ladderOnly }
    }
    return start?.let { maxOf(it, atLeast ?: it) }
}

internal fun runAnchor(run: RunAnswers, known: KnownLoads): RecentMaxima? =
    known.longestRecent[CardioFamily.of(run.activity)] ?: run.seed()

internal fun runWeek(run: RunAnswers, known: KnownLoads): RecentMaxima? = known.longestWeek[CardioFamily.of(run.activity)]

/** Weeks to finish the ladder from [level] at [ladderSessions] a rotation: an all-Easy estimate at one rotation a
 *  week; ratings, the cap, the guard and drops only lengthen it. Null when the plan runs no ladder session. */
internal fun ladderWeeks(level: Int, ladderSessions: Int): Int? = if (ladderSessions == 0) null
    else (RunWalkLadder.EASY_PER_LEVEL * (RunWalkLadder.TOP - level + 1) + ladderSessions - 1) / ladderSessions

/** Null when the counted phases include LADDER and [ladderWeeks] is null. */
internal fun weeksNeeded(goal: RunGoal, start: RunPhase, ladderWeeks: Int?): Int? {
    if (goal.phases.isEmpty()) return if (start == RunPhase.LADDER) ladderWeeks else 0
    val phases = goal.phases.filter { it >= start && it <= requireNotNull(goal.eventPhase) }
    if (RunPhase.LADDER in phases && ladderWeeks == null) return null
    return phases.sumOf { if (it == RunPhase.LADDER) requireNotNull(ladderWeeks) else requireNotNull(it.minWeeks) }
}

/** The longest WORK seconds in the window when the family has a finished log; otherwise the typed time or
 *  distance estimated with [TYPED_START_SECONDS_PER_METRE]. */
internal fun runningSeconds(run: RunAnswers, known: KnownLoads): Int? =
    if (hasLog(run, known)) known.longestRunning[CardioFamily.of(run.activity)] else run.seed()?.seconds
        ?: run.seed()?.metres?.let { (it * TYPED_START_SECONDS_PER_METRE).toInt() }

internal fun ladderStart(runningSeconds: Int?, injury: Boolean): Int {
    val seconds = runningSeconds ?: 0
    val level = (RunWalkLadder.LEVELS.indexOfLast { it.runningSeconds <= seconds } + 1).coerceAtLeast(1)
    return (level - if (injury) 1 else 0).coerceAtLeast(1)
}
