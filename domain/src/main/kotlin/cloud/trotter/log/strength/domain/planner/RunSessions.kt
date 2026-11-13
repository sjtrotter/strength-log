package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.progression.GoalPace
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.Riegel
import cloud.trotter.log.strength.domain.progression.RunWalkLadder

/** What a run day is for; titles are placeholders until PR 7's copy. Stored nowhere in 4a. */
enum class RunSession(val title: String, val quality: Boolean) {
    LADDER("Run/walk", false), EASY("Easy", false), LONG("Long", false),
    THRESHOLD("Threshold", true), VO2MAX("Intervals", true), RACE_PACE("Race pace", true),
    TEST_INTERVALS("Test intervals", true), TEMPO("Tempo", true),
}

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
    todayEpochDay: Long?,
): CardioBlock {
    fun single(end: Measure) = CardioBlock(run.activity, listOf(CardioSetSpec(CardioRole.WORK, end, hard = session.quality)))
    fun intervals(rounds: Int, end: Measure, recovery: Int, aim: Int? = null) = CardioBlock(run.activity, buildList {
        repeat(rounds) { index ->
            if (index > 0) add(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(recovery)))
            add(CardioSetSpec(CardioRole.WORK, end, aim?.let { Measure.Time(it) }, hard = true))
        }
    })
    return when (session) {
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
        RunSession.THRESHOLD -> single(Measure.Time(1_200))
        RunSession.VO2MAX -> intervals(5, Measure.Time(180), 180)
        RunSession.RACE_PACE -> {
            val lastSixWeeks = run.resolvedEvent == RunEvent.M800 && run.dateEpochDay != null &&
                run.dateEpochDay - requireNotNull(todayEpochDay) in 0L until 42L
            val metres = if (lastSixWeeks) 300 else 400
            val aim = racePaceSeconds(run)?.let {
                GoalPace.aimSeconds(it, requireNotNull(run.resolvedEvent).metres.toDouble(), metres)
            }
            intervals(if (lastSixWeeks) 3 else 6, Measure.Distance(metres), if (lastSixWeeks) 240 else 120, aim)
        }
        RunSession.TEST_INTERVALS -> intervals(4, Measure.Distance(400), 120, testAim(run))
        RunSession.TEMPO -> single(Measure.Time(900))
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
