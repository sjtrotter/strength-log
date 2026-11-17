package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program

/** Practice (design §3.2): calendar days wanted between maximal sessions, three in the first weeks or for the
 *  careful group, else two. Advice only; the app cannot measure hours. */
fun spacingDays(firstWeeks: Boolean, careful: Boolean): Int = if (firstWeeks || careful) 3 else 2

/** Calendar days since [lastMaximalEpochDay], or null with no earlier maximal date. */
fun daysSince(lastMaximalEpochDay: Long?, todayEpochDay: Long): Long? = lastMaximalEpochDay?.let { todayEpochDay - it }

/** Design §3.2 tiers (a), (c) and (d): a maximal day still in its intro; a `nordic` slot (not a knee-flexion fallback)
 *  with fewer than three exposures; or, for a New lifter, a MAIN of pattern SQUAT_BILATERAL or HINGE on a maximal day
 *  with fewer than three exposures. Tier (b), the careful group, is [spacingDays]'s other argument. */
fun sprintFirstWeeks(program: Program, newLifter: Boolean): Boolean = program.days.any { day ->
    val maximal = day.cardioRule?.session?.let(SprintRules::maximal) == true
    (maximal && requireNotNull(day.cardioRule).intro > 0) || day.exercises.any { slot ->
        val rule = slot.rule
        rule != null && rule.state.exposures < 3 && (slot.exerciseId == "nordic" ||
            (newLifter && maximal && rule.role == SlotRole.MAIN &&
                ExerciseLibrary.get(slot.exerciseId).pattern in listOf(MovementPattern.SQUAT_BILATERAL, MovementPattern.HINGE)))
    }
}

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

/** Plan-wide sprint history, supplied by the caller at Start and Finish. */
data class SprintContext(
    val layoff: Boolean,
    /** An admitted trial's distance on Sprint A. */
    val timeTrialMetres: Int? = null,
    val cyclesCompleted: Int = 0,
    /** Sprint B runs tempo after A actually ran a 200 or 400 m trial this rotation. */
    val tempo: Boolean = false,
    val careful: Boolean = false,
) {
    init {
        require(cyclesCompleted >= 0)
        require(timeTrialMetres == null || timeTrialMetres > 0)
    }

    companion object {
        /** [maximalEpochDays] contains dates by program day of sessions with at least one finished WORK rep
         *  of a maximal block: normal blocks, restarts and trials. Pain counts; all-skipped sessions do not.
         *  Only sessions before this session belong in the history. Tempo, including B's trial substitute,
         *  never counts. [maximalDaysThisCycle] contains the IDs of
         *  those days already run in this rotation. [program] includes earlier Finish updates.
         *  [cyclesCompleted] is the count before the rotation wraps. B checks layoff before trial substitution. */
        fun of(
            program: Program,
            dayId: String,
            maximalEpochDays: Map<String, List<Long>>,
            cyclesCompleted: Int,
            goal: PlanGoal?,
            todayEpochDay: Long,
            maximalDaysThisCycle: Set<String>,
            careful: Boolean = false,
        ): SprintContext {
            val maximalDays = program.days.filter { day -> day.cardioRule?.session?.let(SprintRules::maximal) == true }
            val dates = maximalDays.flatMap { maximalEpochDays[it.id].orEmpty() }.distinct().sorted()
            fun hasLayoff(id: String): Boolean {
                val ownLast = maximalEpochDays[id]?.maxOrNull()
                val sinceLast = dates.filter { ownLast == null || it >= ownLast } + todayEpochDay
                return maximalDays.any { it.id == id } && sinceLast.zipWithNext().any { (before, after) ->
                    SprintRules.layoff(before, after)
                }
            }
            val layoff = hasLayoff(dayId)
            val first = maximalDays.firstOrNull()
            val second = maximalDays.getOrNull(1)
            val a = first?.cardioRule
            val event = (goal as? PlanGoal.Sprint)?.metres
            val longTrial = event == 200 || event == 400
            val normal = maximalDays.associate { day ->
                val rule = requireNotNull(day.cardioRule)
                day.id to SprintRules.workMetres(sprintBlock(requireNotNull(rule.session), rule.steps))
            }
            val trial = event?.takeIf {
                dayId == first?.id && a?.intro == 0 && a.sprintTrialCycle != cyclesCompleted && !layoff &&
                    (cyclesCompleted + 1) % SprintRules.TIME_TRIAL_EVERY == 0 &&
                    !(longTrial && second != null &&
                        (second.id in maximalDaysThisCycle || hasLayoff(second.id)))
            }
            val trialTotal = trial?.let { SprintRules.workMetres(SprintRules.timeTrial(it, CardioActivity.TRACK_RUN)) }
            val other = normal.filterKeys { it != first?.id && !(longTrial && it == second?.id) }.values.sum()
            val admitted = trialTotal != null && SprintRules.withinRise(normal.values.sum(), other + trialTotal)
            return SprintContext(layoff, trial.takeIf { admitted }, cyclesCompleted,
                tempo = longTrial && dayId == second?.id && a?.sprintTrialCycle == cyclesCompleted, careful = careful)
        }
    }
}

/** Sprint day progression (design §3.1.3, revision 32). */
internal object SprintRules {
    /** "the first three sprint weeks run the low end of every range": three counted sessions per sprint day. */
    const val INTRO_SESSIONS = 3
    /** Practice: six counted sessions per sprint day for the careful group (design §5). */
    const val CAREFUL_INTRO_SESSIONS = 6
    /** Practice: restart after more than 14 days without a maximal sprint. */
    const val GAP_DAYS = 14
    /** Practice: the trial rotation's allowance over planned maximal WORK meters. */
    const val RISE_PERCENT = 15
    const val RISE_FLOOR_METRES = 20
    /** "Time trial every fifth cycle". */
    const val TIME_TRIAL_EVERY = 5

    fun maximal(session: RunSession): Boolean = session.sprint && requireNotNull(RunRange.of(session)).hard

    fun workMetres(block: CardioBlock): Int = block.sets.filter { it.role == CardioRole.WORK }
        .sumOf { (it.end as? Measure.Distance)?.metres ?: 0 }

    fun layoff(beforeEpochDay: Long, afterEpochDay: Long): Boolean = afterEpochDay - beforeEpochDay > GAP_DAYS

    fun withinRise(before: Int, after: Int): Boolean =
        (after - before).toLong() * 100 <= maxOf(before.toLong() * RISE_PERCENT, RISE_FLOOR_METRES * 100L)

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

    fun opening(block: CardioBlock, rule: CardioRule, session: RunSession, sprint: SprintContext): CardioBlock =
        when {
            maximal(session) && sprint.layoff -> sprintBlock(session, 0, block.activity)
            sprint.tempo -> sprintBlock(RunSession.SPRINT_TEMPO, 0, block.activity)
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
    ): CardioOutcome {
        val restart = maximal(session) && sprint.layoff
        fun outcome(next: CardioRule, change: CardioChange) =
            CardioOutcome(sprintBlock(session, next.steps, block.activity), next, change)
        if (!restart && sprint.tempo) return outcome(rule, CardioChange.Repeat)
        if (!restart && trial(rule, sprint, block.activity) != null) {
            return outcome(rule.copy(sprintTrialCycle = sprint.cyclesCompleted), CardioChange.Repeat)
        }
        val start = if (restart) rule.copy(steps = 0, credits = 0,
            intro = if (sprint.careful) CAREFUL_INTRO_SESSIONS else INTRO_SESSIONS) else rule
        val same = if (restart) CardioChange.Restart else CardioChange.Repeat
        val range = requireNotNull(RunRange.of(session))
        val counted = CardioMeasures.fullSuccess(block, results) && !pain && !(range.goodBand && effort == CardioEffort.HARD)
        return when {
            !counted -> outcome(start, same)
            start.intro > 0 -> outcome(start.copy(intro = start.intro - 1), same)
            start.steps == range.top -> outcome(start, same)
            start.credits == 0 -> outcome(start.copy(credits = 1), same)
            start.stepCycle == sprint.cyclesCompleted -> outcome(start, same)
            else -> outcome(start.copy(steps = start.steps + 1, credits = 0,
                stepCycle = sprint.cyclesCompleted), CardioChange.Step)
        }
    }
}
