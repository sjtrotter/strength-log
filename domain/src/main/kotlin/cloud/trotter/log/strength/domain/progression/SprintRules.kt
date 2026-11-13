package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program
import kotlin.math.pow

/** §3.1.3: "Every sprint day opens with a 10 min build-up block before the first maximal rep". */
private val BUILD_UP = CardioSetSpec(CardioRole.WARMUP, Measure.Time(600))

/** A sprint day's block at [steps]: the build-up, then the range block; tempo is not maximal and has none. */
internal fun sprintBlock(
    session: RunSession,
    steps: Int,
    activity: CardioActivity = CardioActivity.TRACK_RUN,
): CardioBlock {
    val block = requireNotNull(RunRange.of(session)).block(steps, activity)
    return if (SprintRules.maximal(session)) block.copy(sets = listOf(BUILD_UP) + block.sets) else block
}

/** What a sprint day reads beyond its own block and rule (design §3.1.3). Build it with [of]. */
data class SprintContext(
    /** Epoch day of the day's last finished session before this one; null when it has none. */
    val lastEpochDay: Long?,
    /** WORK metres of the plan's other maximal sprint days at their rules' steps. */
    val otherMetres: Int,
    /** The goal's metres when this rotation's Sprint A runs the time trial; else null. */
    val timeTrialMetres: Int?,
) {
    init { require(otherMetres >= 0 && (timeTrialMetres == null || timeTrialMetres > 0)) }

    companion object {
        /** For [dayId] of [program]. [cyclesCompleted] is `plan.cyclesCompleted` before this rotation wraps; Sprint A
         *  is the program's first day with a maximal sprint rule. */
        fun of(program: Program, dayId: String, lastEpochDay: Long?, cyclesCompleted: Int, goal: PlanGoal?): SprintContext {
            val maximalDays = program.days.filter { day -> day.cardioRule?.session?.let(SprintRules::maximal) == true }
            val otherMetres = maximalDays.filter { it.id != dayId }.sumOf { day ->
                val rule = requireNotNull(day.cardioRule)
                SprintRules.workMetres(sprintBlock(requireNotNull(rule.session), rule.steps))
            }
            val trial = if (dayId == maximalDays.firstOrNull()?.id &&
                (cyclesCompleted + 1) % SprintRules.TIME_TRIAL_EVERY == 0) (goal as? PlanGoal.Sprint)?.metres else null
            return SprintContext(lastEpochDay, otherMetres, trial)
        }
    }
}

/** Sprint day progression (design §3.1.3, revision 32). */
internal object SprintRules {
    /** "the first three sprint weeks run the low end of every range": three counted sessions per sprint day. */
    const val INTRO_SESSIONS = 3
    /** "a 14-day gap resets to intro volume", counted beyond the day's recurrence, as a lift layoff is. */
    const val GAP_DAYS = 14
    /** A sprint day recurs once a rotation; §2.1's rotation is a week. */
    const val RECURRENCE_DAYS = 7
    /** "weekly max-speed meters rise at most 15%". */
    const val WEEKLY_RISE = 1.15
    /** "Time trial every fifth cycle". */
    const val TIME_TRIAL_EVERY = 5

    fun maximal(session: RunSession): Boolean = session.sprint && requireNotNull(RunRange.of(session)).hard

    fun workMetres(block: CardioBlock): Int = block.sets.filter { it.role == CardioRole.WORK }
        .sumOf { (it.end as? Measure.Distance)?.metres ?: 0 }

    fun gap(sprint: SprintContext, todayEpochDay: Long): Boolean =
        sprint.lastEpochDay?.let { todayEpochDay - it - RECURRENCE_DAYS >= GAP_DAYS } == true

    fun withinRise(before: Int, after: Int, held: Int): Boolean =
        after <= before * WEEKLY_RISE.pow(1.0 + held) + 1e-9

    fun timeTrial(metres: Int, activity: CardioActivity): CardioBlock = CardioBlock(activity,
        listOf(BUILD_UP, CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = true)))

    fun opening(block: CardioBlock, rule: CardioRule, session: RunSession, sprint: SprintContext, todayEpochDay: Long): CardioBlock =
        when {
            gap(sprint, todayEpochDay) -> sprintBlock(session, 0, block.activity)
            sprint.timeTrialMetres != null && rule.intro == 0 -> timeTrial(sprint.timeTrialMetres, block.activity)
            else -> block
        }

    fun afterSession(
        block: CardioBlock,
        rule: CardioRule,
        session: RunSession,
        results: List<CardioSetResult>,
        effort: CardioEffort?,
        pain: Boolean,
        sprint: SprintContext,
        todayEpochDay: Long,
    ): CardioOutcome {
        val gap = gap(sprint, todayEpochDay)
        fun outcome(next: CardioRule, change: CardioChange) =
            CardioOutcome(sprintBlock(session, next.steps, block.activity), next, change)
        if (!gap && sprint.timeTrialMetres != null && rule.intro == 0) return outcome(rule, CardioChange.Repeat)
        val start = if (gap) rule.copy(steps = 0, credits = 0, held = 0, intro = INTRO_SESSIONS) else rule
        val same = if (gap) CardioChange.Restart else CardioChange.Repeat
        val range = requireNotNull(RunRange.of(session))
        val counted = CardioMeasures.fullSuccess(block, results) && !pain && !(range.goodBand && effort == CardioEffort.HARD)
        fun metres(steps: Int) = workMetres(sprintBlock(session, steps))
        return when {
            !counted -> outcome(start, same)
            start.intro > 0 -> outcome(start.copy(intro = start.intro - 1), same)
            start.steps == range.top -> outcome(start, same)
            start.credits == 0 -> outcome(start.copy(credits = 1), same)
            maximal(session) && !withinRise(sprint.otherMetres + metres(start.steps),
                sprint.otherMetres + metres(start.steps + 1), start.held) -> outcome(start.copy(held = start.held + 1), same)
            else -> outcome(start.copy(steps = start.steps + 1, credits = 0, held = 0), CardioChange.Step)
        }
    }
}
