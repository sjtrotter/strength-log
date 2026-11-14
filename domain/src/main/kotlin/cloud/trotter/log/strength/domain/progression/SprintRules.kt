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
    /** Stored maximal WORK meters on the plan's other sprint days, excluding tempo. */
    val otherMetres: Int,
    /** An admitted trial's distance on Sprint A. */
    val timeTrialMetres: Int?,
    /** Sum of every maximal sprint day's stored-step WORK meters. */
    val plannedWeekMetres: Int,
    /** Planned meters before this rotation's Finish increases. */
    val rotationStartMetres: Int = plannedWeekMetres,
    val cyclesCompleted: Int = 0,
    /** Longest wait among other maximal sprint days with a step credit. */
    val otherWait: Int = 0,
    /** Sprint B runs tempo in an admitted 200 or 400 m trial rotation. */
    val tempo: Boolean = false,
) {
    init {
        require(otherMetres >= 0 && plannedWeekMetres >= 0 && rotationStartMetres >= 0)
        require(cyclesCompleted >= 0 && otherWait >= 0)
        require(timeTrialMetres == null || timeTrialMetres > 0)
    }

    companion object {
        /** [lastEpochDays] contains dates of finished maximal sprint cardio sessions only, by program day,
         *  before this session: no plyometrics, lifts or tempo. [program] includes earlier Finish updates.
         *  [cyclesCompleted] is the count before the rotation wraps. */
        fun of(
            program: Program,
            dayId: String,
            lastEpochDays: Map<String, Long>,
            cyclesCompleted: Int,
            goal: PlanGoal?,
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
            val planned = normal.values.sum()
            val second = maximalDays.getOrNull(1)
            val longTrial = trial == 200 || trial == 400
            val trialTotal = trial?.let { SprintRules.workMetres(SprintRules.timeTrial(it, CardioActivity.TRACK_RUN)) }
            val trialWeek = normal.filterKeys { it != first?.id && !(longTrial && it == second?.id) }.values.sum()
            val admitted = trialTotal != null && SprintRules.withinRise(planned, trialWeek + trialTotal)
            val rise = maximalDays.sumOf { day ->
                val rule = requireNotNull(day.cardioRule)
                if (rule.sprintStepCycle == cyclesCompleted) normal.getValue(day.id) -
                    SprintRules.workMetres(sprintBlock(requireNotNull(rule.session), rule.sprintStepFrom)) else 0
            }
            val otherWait = maximalDays.filter { it.id != dayId }.mapNotNull { it.cardioRule }
                .filter { it.credits > 0 }.maxOfOrNull { it.sprintWait } ?: 0
            val last = maximalDays.mapNotNull { lastEpochDays[it.id] }.maxOrNull()
            return SprintContext(last, normal.filterKeys { it != dayId }.values.sum(),
                trial.takeIf { admitted && dayId == first?.id }, planned, planned - rise,
                cyclesCompleted, otherWait, tempo = admitted && longTrial && dayId == second?.id)
        }
    }
}

/** Sprint day progression (design §3.1.3, revision 32). */
internal object SprintRules {
    /** "the first three sprint weeks run the low end of every range": three counted sessions per sprint day. */
    const val INTRO_SESSIONS = 3
    /** Practice: reset 14 days after the plan's last finished maximal sprint of any kind (audit row 8). */
    const val GAP_DAYS = 14
    /** Practice: at most 15% more planned maximal WORK meters per rotation (audit row 5). */
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
        sprint.timeTrialMetres?.takeIf { rule.intro == 0 }?.let { timeTrial(it, activity) }

    fun opening(block: CardioBlock, rule: CardioRule, session: RunSession, sprint: SprintContext, todayEpochDay: Long): CardioBlock =
        when {
            sprint.tempo -> sprintBlock(RunSession.SPRINT_TEMPO, 0, block.activity)
            maximal(session) && gap(sprint, todayEpochDay) -> sprintBlock(session, 0, block.activity)
            else -> trial(rule, sprint, block.activity) ?: sprintBlock(session, rule.steps, block.activity)
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
        val maximal = maximal(session)
        val gap = maximal && gap(sprint, todayEpochDay)
        fun outcome(next: CardioRule, change: CardioChange) =
            CardioOutcome(sprintBlock(session, next.steps, block.activity), next, change)
        if (sprint.tempo || (!gap && trial(rule, sprint, block.activity) != null)) return outcome(rule, CardioChange.Repeat)
        val start = if (gap) rule.copy(steps = 0, credits = 0, intro = INTRO_SESSIONS,
            sprintWait = 0, sprintStepCycle = null, sprintStepFrom = 0) else rule
        val same = if (gap) CardioChange.Restart else CardioChange.Repeat
        val range = requireNotNull(RunRange.of(session))
        val counted = CardioMeasures.fullSuccess(block, results) && !pain && !(range.goodBand && effort == CardioEffort.HARD)
        fun metres(steps: Int) = workMetres(sprintBlock(session, steps))
        val proposed = range.nextStep(start.steps)
        fun fits(steps: Int): Boolean {
            val after = sprint.otherMetres + metres(steps)
            return withinRise(sprint.otherMetres + metres(start.steps), after) &&
                withinRise(sprint.rotationStartMetres, after)
        }
        val next = if (!maximal || fits(proposed)) proposed else start.steps + 1
        fun wait() = outcome(start.copy(sprintWait = start.sprintWait + 1), same)
        return when {
            !counted -> outcome(start, same)
            start.intro > 0 -> outcome(start.copy(intro = start.intro - 1), same)
            start.steps == range.top -> outcome(start, same)
            start.credits == 0 -> outcome(start.copy(credits = 1), same)
            maximal && (sprint.otherWait > start.sprintWait || !fits(next)) -> wait()
            else -> outcome(start.copy(steps = next, credits = 0, sprintWait = 0,
                sprintStepCycle = if (maximal) sprint.cyclesCompleted else null,
                sprintStepFrom = if (maximal) {
                    if (start.sprintStepCycle == sprint.cyclesCompleted) start.sprintStepFrom else start.steps
                } else 0), CardioChange.Step)
        }
    }
}
