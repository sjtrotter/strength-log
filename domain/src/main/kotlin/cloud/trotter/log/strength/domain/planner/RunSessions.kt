package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.GoalPace
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.Riegel
import cloud.trotter.log.strength.domain.progression.RunRange
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.RunWalkLadder

private const val EASY_SECONDS = 1_200
private const val STRENGTH_SECONDS = 1_500

internal fun easyLimit(long: CardioBlock?): Int? = long?.let { maxOf(60, (3 * plannedSeconds(it) / 4) / 60 * 60) }

internal fun easySeconds(minutes: Int, strength: Boolean, long: CardioBlock?): Int = minOf(
    maxOf(EASY_SECONDS, minutes * 60 - SessionBudget.WARM_UP_SECONDS - if (strength) STRENGTH_SECONDS else 0),
    easyLimit(long) ?: Int.MAX_VALUE,
)

internal fun runBlock(
    session: RunSession,
    run: RunAnswers,
    phase: RunPhase?,
    anchor: RecentMaxima?,
    easySeconds: Int,
    ladderLevel: Int,
): CardioBlock {
    fun single(end: Measure) = CardioBlock(run.activity, listOf(CardioSetSpec(CardioRole.WORK, end)))
    return when (session) {
        RunSession.ACCELERATION, RunSession.MAX_VELOCITY, RunSession.SPRINT_ENDURANCE, RunSession.SPRINT_TEMPO ->
            error("Sprint blocks come from sprintBlock")
        RunSession.LADDER -> RunWalkLadder.block(ladderLevel, run.activity)
        RunSession.EASY -> single(Measure.Time(easySeconds))
        RunSession.LONG -> {
            val time = when {
                run.goal == RunGoal.MIDDLE_DISTANCE -> 3_600
                phase == RunPhase.HALF_BUILD -> 7_200
                phase == RunPhase.MARATHON_BUILD -> 10_800
                phase == RunPhase.ULTRA_BUILD -> if (run.resolvedEvent == RunEvent.K50) 12_600 else 14_400
                else -> 3_600
            }
            val distance = when (phase) {
                RunPhase.HALF_BUILD -> 18_000
                RunPhase.MARATHON_BUILD -> 28_000
                RunPhase.ULTRA_BUILD -> when (run.resolvedEvent) {
                    RunEvent.K50 -> 30_000
                    RunEvent.MI50 -> 48_280
                    else -> null
                }
                else -> if (run.goal == RunGoal.MIDDLE_DISTANCE) null else 10_000
            }
            single(if (distance != null && (anchor == null || anchor.metres != null)) Measure.Distance(distance) else Measure.Time(time))
        }
        RunSession.THRESHOLD, RunSession.TEMPO, RunSession.VO2MAX, RunSession.RACE_PACE,
        RunSession.SHARPENING, RunSession.TEST_INTERVALS -> {
            val range = requireNotNull(RunRange.of(session))
            val block = range.block(0, run.activity)
            val aim = when (session) {
                RunSession.RACE_PACE, RunSession.SHARPENING -> racePaceSeconds(run)?.let {
                    GoalPace.aimSeconds(it, requireNotNull(run.resolvedEvent).metres.toDouble(), range.rep.first)
                }
                RunSession.TEST_INTERVALS -> testAim(run)
                else -> null
            }
            if (aim == null) block else block.copy(sets = block.sets.map {
                if (it.role == CardioRole.WORK) it.copy(aim = Measure.Time(aim)) else it
            })
        }
    }
}

private fun racePaceSeconds(run: RunAnswers): Double? = run.goalSeconds?.toDouble() ?: run.recent?.let {
    Riegel.predictedSeconds(it.metres.toDouble(), it.seconds.toDouble(), requireNotNull(run.resolvedEvent).metres.toDouble())
}

private fun testAim(run: RunAnswers): Int? = when (run.test) {
    FitnessTest.TWO_MILE, FitnessTest.ONE_AND_HALF_MILE -> run.goalSeconds?.let {
        GoalPace.aimSeconds(it.toDouble(), requireNotNull(run.test.metres).toDouble(), 400, GoalPace.TEST_FACTOR)
    }
    FitnessTest.COOPER -> run.testScore?.let { GoalPace.aimSeconds(720.0, it.toDouble(), 400, GoalPace.TEST_FACTOR) }
    FitnessTest.PACER, FitnessTest.HAMR -> null
}

internal fun sharpening(run: RunAnswers, todayEpochDay: Long?): Boolean =
    run.resolvedEvent == RunEvent.M800 && run.dateEpochDay != null &&
        run.dateEpochDay - requireNotNull(todayEpochDay) in 0L until 42L

internal fun runRule(
    session: RunSession,
    run: RunAnswers,
    phase: RunPhase?,
    level: Int,
    block: CardioBlock,
    lone: Boolean,
): CardioRule = when (session) {
    RunSession.LADDER -> CardioRule(level)
    RunSession.LONG -> {
        val distance = block.sets.first { it.role == CardioRole.WORK }.end is Measure.Distance
        val (metres, seconds) = longTarget(run, phase, distance)
        CardioRule(null, session, longMetres = metres, longSeconds = seconds)
    }
    else -> CardioRule(null, session,
        alternate = RunSession.VO2MAX.takeIf { session == RunSession.THRESHOLD && phase == RunPhase.BASE && lone })
}

private fun longTarget(run: RunAnswers, phase: RunPhase?, distance: Boolean): Pair<Int?, Int?> = when {
    run.goal == RunGoal.MIDDLE_DISTANCE -> null to 4_500
    phase == RunPhase.HALF_BUILD -> if (distance) 21_000 to null else null to 7_200
    phase == RunPhase.MARATHON_BUILD -> if (distance) 32_000 to null else null to 10_800
    phase == RunPhase.ULTRA_BUILD -> when (run.resolvedEvent) {
        RunEvent.K50 -> (40_000.takeIf { distance }) to 12_600
        RunEvent.MI50 -> if (distance) 64_374 to null else null to 21_600
        else -> null to 21_600
    }
    else -> null to 5_400
}
