package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
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

/** Design §4.2's ledger meters: prescribed distance of every FINISHED hard WORK set, never GPS readings.
 *  Tempo and its substitute read 0. A positive session is a maximal date; Pain counts, all-skipped does not. */
fun sprintMaximalMetres(results: List<CardioSetResult>): Int = results.filter {
    it.status == CardioSetStatus.FINISHED && it.spec.role == CardioRole.WORK && it.spec.hard
}.sumOf { (it.spec.end as? Measure.Distance)?.metres ?: 0 }

/** One finished session on a plan day; [epochDay] is the local date of its start. */
data class SprintRecord(val dayId: String, val epochDay: Long, val results: List<CardioSetResult>)

internal enum class SprintDose { NORMAL, RESTART, TEMPO, TRIAL, SHORTENED, SUBSTITUTE }
internal data class SprintDecision(val dose: SprintDose, val block: CardioBlock)

/** Plan-wide sprint history, supplied by the caller at Start and Finish. */
data class SprintContext(
    val layoff: Boolean,
    /** An admitted trial's distance on Sprint A. */
    val timeTrialMetres: Int? = null,
    val cyclesCompleted: Int = 0,
    /** Sprint B runs tempo after A actually ran a 200 or 400 m trial this rotation. */
    val tempo: Boolean = false,
    val careful: Boolean = false,
    /** Meters left in the rolling seven days for a normal decision; null without a ledger. */
    val weekLeft: Int? = null,
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
         *  [cyclesCompleted] is the count before the rotation wraps. B checks layoff before trial substitution.
         *  The cap is a product exposure limit relative to the plan [P], informed only by weak team-sport
         *  associations (Duhig 2016, Malone 2017), not a validated threshold; copy never calls it safe. */
        fun of(
            program: Program,
            dayId: String,
            maximalEpochDays: Map<String, List<Long>>,
            cyclesCompleted: Int,
            goal: PlanGoal?,
            todayEpochDay: Long,
            maximalDaysThisCycle: Set<String>,
            careful: Boolean = false,
            maximalMetresByEpochDay: Map<Long, Int>? = null,
            trialEpochDays: Set<Long> = emptySet(),
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
            val window = todayEpochDay - 6..todayEpochDay
            val recent = maximalMetresByEpochDay?.filterKeys { it in window }?.values?.sum() ?: 0
            val planned = normal.values.sum()
            val largest = normal.values.maxOrNull() ?: 0
            val trialMetres = event?.let { SprintRules.workMetres(SprintRules.timeTrial(it, CardioActivity.TRACK_RUN)) } ?: 0
            val trialInWindow = trialEpochDays.any { it in window }
            val weekLeft = maximalMetresByEpochDay?.let {
                planned + maxOf(largest, if (trialInWindow) trialMetres else 0) - recent
            }
            val trial = event?.takeIf {
                dayId == first?.id && (maximalMetresByEpochDay == null || dayId !in maximalDaysThisCycle) &&
                    a?.intro == 0 && a.sprintTrialCycle != cyclesCompleted && !layoff &&
                    (cyclesCompleted + 1) % SprintRules.TIME_TRIAL_EVERY == 0 &&
                    !(longTrial && second != null &&
                        (second.id in maximalDaysThisCycle || hasLayoff(second.id)))
            }
            val trialTotal = trial?.let { SprintRules.workMetres(SprintRules.timeTrial(it, CardioActivity.TRACK_RUN)) }
            val other = normal.filterKeys { it != first?.id && !(longTrial && it == second?.id) }.values.sum()
            val admitted = trialTotal != null && SprintRules.withinRise(planned, other + trialTotal) &&
                (maximalMetresByEpochDay == null || trialTotal <= planned + maxOf(largest, trialMetres) - recent)
            return SprintContext(layoff, trial.takeIf { admitted }, cyclesCompleted,
                tempo = longTrial && dayId == second?.id && a?.sprintTrialCycle == cyclesCompleted, careful = careful,
                weekLeft = weekLeft)
        }

        /** Derives the allowance and rotation membership from sessions ordered before this one. */
        fun ofHistory(
            program: Program,
            dayId: String,
            history: List<SprintRecord>,
            cyclesCompleted: Int,
            goal: PlanGoal?,
            todayEpochDay: Long,
            careful: Boolean,
        ): SprintContext {
            val maximal = program.days.filter { it.cardioRule?.session?.let(SprintRules::maximal) == true }
            val ids = maximal.map { it.id }.toSet()
            val records = history.filter { it.dayId in ids && sprintMaximalMetres(it.results) > 0 }
            val first = maximal.firstOrNull()
            val trialRep = first?.cardioRule?.session?.let(RunRange::of)?.rep?.last
            val trials = records.filter { record ->
                record.dayId == first?.id && trialRep != null && record.results.any {
                    it.spec.role == CardioRole.WORK && ((it.spec.end as? Measure.Distance)?.metres ?: 0) > trialRep
                }
            }.map { it.epochDay }.toSet()
            return of(program, dayId, records.groupBy { it.dayId }.mapValues { (_, rows) -> rows.map { it.epochDay } },
                cyclesCompleted, goal, todayEpochDay,
                maximal.filter { it.cardioRule?.maximalCycle == cyclesCompleted }.map { it.id }.toSet(), careful,
                records.groupBy { it.epochDay }.mapValues { (_, rows) -> rows.sumOf { sprintMaximalMetres(it.results) } }, trials)
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

    /** Design §4.2's order: restart, B's tempo after a completed trial, trial, normal; maximal doses fit the allowance. */
    internal fun decision(rule: CardioRule, session: RunSession, sprint: SprintContext, activity: CardioActivity): SprintDecision {
        fun fitted(kind: SprintDose, block: CardioBlock): SprintDecision {
            val left = sprint.weekLeft ?: return SprintDecision(kind, block)
            if (workMetres(block) <= left) return SprintDecision(kind, block)
            if (kind == SprintDose.NORMAL) {
                val minimum = requireNotNull(RunRange.of(session)).rounds.first
                var shortened = block
                while (workMetres(shortened) > left && shortened.sets.count { it.role == CardioRole.WORK } > minimum) {
                    val last = shortened.sets.indexOfLast { it.role == CardioRole.WORK }
                    shortened = shortened.copy(sets = shortened.sets.take(last - 1))
                }
                if (workMetres(shortened) <= left) return SprintDecision(SprintDose.SHORTENED, shortened)
            }
            return SprintDecision(SprintDose.SUBSTITUTE, sprintBlock(RunSession.SPRINT_TEMPO, 0, activity))
        }
        return when {
            maximal(session) && sprint.layoff -> fitted(SprintDose.RESTART, sprintBlock(session, 0, activity))
            sprint.tempo -> SprintDecision(SprintDose.TEMPO, sprintBlock(RunSession.SPRINT_TEMPO, 0, activity))
            else -> {
                val trial = trial(rule, sprint, activity)
                when {
                    trial != null -> SprintDecision(SprintDose.TRIAL, trial)
                    maximal(session) -> fitted(SprintDose.NORMAL, sprintBlock(session, rule.steps, activity))
                    else -> SprintDecision(SprintDose.NORMAL, sprintBlock(session, rule.steps, activity))
                }
            }
        }
    }

    fun opening(block: CardioBlock, rule: CardioRule, session: RunSession, sprint: SprintContext): CardioBlock =
        decision(rule, session, sprint, block.activity).block

    fun afterSession(
        block: CardioBlock,
        rule: CardioRule,
        session: RunSession,
        results: List<CardioSetResult>,
        effort: CardioEffort?,
        pain: Boolean,
        sprint: SprintContext,
        budgetSeconds: Int? = null,
    ): CardioOutcome {
        val decision = decision(rule, session, sprint, block.activity)
        fun outcome(next: CardioRule, change: CardioChange) =
            CardioOutcome(sprintBlock(session, next.steps, block.activity), next, change)
        when (decision.dose) {
            SprintDose.TEMPO, SprintDose.SHORTENED, SprintDose.SUBSTITUTE -> return outcome(rule, CardioChange.Repeat)
            SprintDose.TRIAL -> return outcome(rule.copy(sprintTrialCycle = sprint.cyclesCompleted), CardioChange.Repeat)
            SprintDose.RESTART, SprintDose.NORMAL -> Unit
        }
        val restart = decision.dose == SprintDose.RESTART
        val start = if (restart) rule.copy(steps = 0, credits = 0,
            intro = if (sprint.careful) CAREFUL_INTRO_SESSIONS else INTRO_SESSIONS) else rule
        val same = if (restart) CardioChange.Restart else CardioChange.Repeat
        val range = requireNotNull(RunRange.of(session))
        val counted = CardioMeasures.fullSuccess(block, results) && !pain && !(range.goodBand && effort == CardioEffort.HARD) &&
            block.sets.filter { it.role == CardioRole.WORK }.map { it.end } ==
            decision.block.sets.filter { it.role == CardioRole.WORK }.map { it.end }
        return when {
            !counted -> outcome(start, same)
            start.intro > 0 -> outcome(start.copy(intro = start.intro - 1), same)
            start.steps == range.top -> outcome(start, same)
            start.credits == 0 -> outcome(start.copy(credits = 1), same)
            start.stepCycle == sprint.cyclesCompleted -> outcome(start, same)
            budgetSeconds != null && plannedSeconds(sprintBlock(session, start.steps + 1, block.activity)) > budgetSeconds &&
                plannedSeconds(sprintBlock(session, start.steps + 1, block.activity)) > plannedSeconds(block) -> outcome(start, same)
            else -> outcome(start.copy(steps = start.steps + 1, credits = 0,
                stepCycle = sprint.cyclesCompleted), CardioChange.Step)
        }
    }
}
