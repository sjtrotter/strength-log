package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program

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

/** Plan-wide sprint history and exposure, supplied by the caller at Start and Finish. */
data class SprintContext(
    /** Latest finished acceleration, max-velocity or sprint-endurance session in this plan. */
    val lastEpochDay: Long?,
    /** This week's other maximal sprint days, including an admitted time trial. */
    val otherMetres: Int,
    /** The goal's distance when Sprint A is due for a trial; admission also checks the weekly cap. */
    val timeTrialMetres: Int?,
    /** Previous week's maximal WORK meters, including trials; fixed throughout this rotation. */
    val previousWeekMetres: Int,
) {
    init {
        require(otherMetres >= 0 && previousWeekMetres >= 0)
        require(timeTrialMetres == null || timeTrialMetres > 0)
    }

    companion object {
        /** [lastEpochDays] contains finished-session dates by program day, before this session.
         *  [previousWeekMetres] stays fixed as [program] receives Finish updates within the rotation.
         *  [cyclesCompleted] is the count before the rotation wraps. */
        fun of(
            program: Program,
            dayId: String,
            lastEpochDays: Map<String, Long>,
            cyclesCompleted: Int,
            goal: PlanGoal?,
            previousWeekMetres: Int,
        ): SprintContext {
            val maximalDays = program.days.filter { day -> day.cardioRule?.session?.let(SprintRules::maximal) == true }
            val normal = maximalDays.associate { day ->
                val rule = requireNotNull(day.cardioRule)
                day.id to SprintRules.workMetres(sprintBlock(requireNotNull(rule.session), rule.steps))
            }
            val first = maximalDays.firstOrNull()
            val trial = (goal as? PlanGoal.Sprint)?.metres?.takeIf {
                first?.cardioRule?.intro == 0 && (cyclesCompleted + 1) % SprintRules.TIME_TRIAL_EVERY == 0
            }
            val trialTotal = trial?.let { SprintRules.workMetres(SprintRules.timeTrial(it, CardioActivity.TRACK_RUN)) }
            val admitted = first != null && trialTotal != null && SprintRules.withinRise(previousWeekMetres,
                normal.values.sum() - normal.getValue(first.id) + trialTotal)
            val otherMetres = normal.filterKeys { it != dayId }.map { (id, metres) ->
                if (admitted && id == first?.id) requireNotNull(trialTotal) else metres
            }.sum()
            val last = maximalDays.mapNotNull { lastEpochDays[it.id] }.maxOrNull()
            return SprintContext(last, otherMetres, trial.takeIf { dayId == first?.id }, previousWeekMetres)
        }
    }
}

/** Sprint day progression (design §3.1.3, revision 32). */
internal object SprintRules {
    /** "the first three sprint weeks run the low end of every range": three counted sessions per sprint day. */
    const val INTRO_SESSIONS = 3
    /** Practice: reset 14 days after the plan's last finished maximal sprint of any kind (audit row 8). */
    const val GAP_DAYS = 14
    /** Practice: at most 15% more maximal WORK meters than the previous week, without compounding (audit row 5). */
    const val WEEKLY_RISE = 1.15
    /** "Time trial every fifth cycle". */
    const val TIME_TRIAL_EVERY = 5

    fun maximal(session: RunSession): Boolean = session.sprint && requireNotNull(RunRange.of(session)).hard

    fun workMetres(block: CardioBlock): Int = block.sets.filter { it.role == CardioRole.WORK }
        .sumOf { (it.end as? Measure.Distance)?.metres ?: 0 }

    fun gap(sprint: SprintContext, todayEpochDay: Long): Boolean =
        sprint.lastEpochDay?.let { todayEpochDay - it >= GAP_DAYS } == true

    fun withinRise(before: Int, after: Int): Boolean = after <= before * WEEKLY_RISE + 1e-9

    /** Practice: best of two at 40 yd and 60 m, one rep at longer events (audit row 9). */
    fun timeTrial(metres: Int, activity: CardioActivity): CardioBlock = CardioBlock(activity, buildList {
        add(BUILD_UP)
        add(CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = true))
        if (metres == 37 || metres == 60) {
            val perMetre = requireNotNull(RunRange.of(RunSession.ACCELERATION)?.recoveryPerMetre)
            val recovery = (metres * perMetre + 59) / 60 * 60
            add(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(recovery)))
            add(CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = true))
        }
    })

    private fun trial(rule: CardioRule, sprint: SprintContext, activity: CardioActivity): CardioBlock? =
        sprint.timeTrialMetres?.takeIf { rule.intro == 0 }?.let { timeTrial(it, activity) }?.takeIf {
            withinRise(sprint.previousWeekMetres, sprint.otherMetres + workMetres(it))
        }

    // A shorter trial can reduce last week's exposure; returning to the stored block uses the same cap.
    private fun openingSteps(rule: CardioRule, session: RunSession, sprint: SprintContext): Int =
        if (!maximal(session)) rule.steps else (rule.steps downTo 0).firstOrNull {
            withinRise(sprint.previousWeekMetres, sprint.otherMetres + workMetres(sprintBlock(session, it)))
        } ?: 0

    fun opening(block: CardioBlock, rule: CardioRule, session: RunSession, sprint: SprintContext, todayEpochDay: Long): CardioBlock =
        when {
            gap(sprint, todayEpochDay) -> sprintBlock(session, 0, block.activity)
            else -> trial(rule, sprint, block.activity) ?: sprintBlock(session, openingSteps(rule, session, sprint), block.activity)
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
        if (!gap && trial(rule, sprint, block.activity) != null) return outcome(rule, CardioChange.Repeat)
        val start = if (gap) rule.copy(steps = 0, credits = 0, intro = INTRO_SESSIONS)
        else rule.copy(steps = openingSteps(rule, session, sprint))
        val same = if (gap) CardioChange.Restart else CardioChange.Repeat
        val range = requireNotNull(RunRange.of(session))
        val counted = CardioMeasures.fullSuccess(block, results) && !pain && !(range.goodBand && effort == CardioEffort.HARD)
        fun metres(steps: Int) = workMetres(sprintBlock(session, steps))
        val proposed = range.nextStep(start.steps)
        val next = if (!maximal(session) || withinRise(sprint.previousWeekMetres, sprint.otherMetres + metres(proposed))) proposed
        else start.steps + 1
        return when {
            !counted -> outcome(start, same)
            start.intro > 0 -> outcome(start.copy(intro = start.intro - 1), same)
            start.steps == range.top -> outcome(start, same)
            start.credits == 0 -> outcome(start.copy(credits = 1), same)
            maximal(session) && !withinRise(sprint.previousWeekMetres,
                sprint.otherMetres + metres(next)) -> outcome(start, same)
            else -> outcome(start.copy(steps = next, credits = 0), CardioChange.Step)
        }
    }
}
