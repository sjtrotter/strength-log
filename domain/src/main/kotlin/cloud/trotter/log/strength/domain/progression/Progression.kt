package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.endValue
import cloud.trotter.log.strength.domain.units.GeneratedLoad
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.math.ceil
import kotlin.math.floor

/** The log to next targets for a lift slot or a cardio day. Pure: callers pass the slot's stored rows and rule and write back
 *  [Outcome.rows] and [Outcome.rule] together; cardio callers pass the day's block and rule and write back [CardioOutcome]. */
object Progression {
    /** The 65+ path's age, read from the profile for plans whose setup did not ask; a setup plan's own answer wins. */
    const val OLDER_ADULT_AGE = 65

    /** A New lifter keeps recalibration past 14 days while the first logged session is under this many days old
     *  (six months; design §3.2 Layoff). */
    const val NEW_LIFTER_LOG_DAYS = 182

    /** A met hold's step at its cadence (#643); the seconds stepper's step, so targets stay on its grid. */
    const val HOLD_STEP_SECONDS = 5

    /** [planned] is the run's opening view; actual rows identify it through plannedIndex. */
    data class Exposure(
        val planned: List<LoggedSet>,
        val actual: List<LoggedSet>,
        val epochDay: Long,
        val layoff: LayoffKind = LayoffKind.None,
    )

    /** [equipment] is the exercise's library equipment; [baseCadence] is the plan's floor for everyExposures
     *  (the experience answer on a generated plan, 1 on user-built and converted plans). */
    data class Context(
        val unit: WeightUnit,
        val equipment: Set<Equipment>,
        val steps: LoadSteps,
        val todayEpochDay: Long,
        val light: Boolean = false,
        val baseCadence: Int = 1,
        /** The exercise's [cloud.trotter.log.strength.domain.library.ExerciseEntry.holdCeilingSeconds]; null keeps a hold's seconds. */
        val holdCeilingSeconds: Int? = null,
    )

    /** ceil(7 × strength entries ÷ (strength days a week × strength entries with the exercise per rotation)). */
    fun expectedRecurrenceDays(trainingEntries: Int, daysPerWeek: Int, appearancesPerRotation: Int): Int =
        ceil(7.0 * trainingEntries / (daysPerWeek * appearancesPerRotation)).toInt()

    /** Rows a run opens with: layoff scaling and the light cycle applied as a view. Nothing is written back.
     *  [lastTrainedEpochDay] is the exercise's last session anywhere in the plan; [expectedRecurrenceDays] comes from the plan.
     *  [newLifter] (New with under six months in the log) keeps recalibration past 14 days.
     *  On the [olderAdult] path, under 16 slot exposures does too;
     *  from 16 exposures, 15–42 days uses ×0.85 and ceil(2/3) working sets, then recalibrates past 42.
     *  The 16-exposure threshold (about eight weeks at twice a week) and restart doses are practice,
     *  informed by Lemmer 2000 and Yang 2022 (audit 2026-11-13, row 11). */
    fun atStart(
        rule: SlotRule,
        working: List<LoggedSet>,
        lastTrainedEpochDay: Long?,
        expectedRecurrenceDays: Int,
        ctx: Context,
        olderAdult: Boolean = false,
        newLifter: Boolean = false,
    ): Opening {
        val layoffDays = lastTrainedEpochDay?.let { ctx.todayEpochDay - it - expectedRecurrenceDays } ?: 0L
        val layoff = when {
            layoffDays <= 3 -> LayoffKind.None
            layoffDays <= 7 -> LayoffKind.Hold
            layoffDays <= 14 -> LayoffKind.Scaled(0.95)
            (if (olderAdult) rule.state.exposures < 16 else newLifter) -> LayoffKind.Recalibrate
            layoffDays <= 42 -> LayoffKind.Scaled(0.85)
            else -> LayoffKind.Recalibrate
        }
        val factor = when (layoff) {
            is LayoffKind.Scaled -> layoff.factor
            LayoffKind.Recalibrate -> 0.75
            else -> null
        }
        var rows = working
        val anchor = WorkingRows.workingWeight(working)
        if (factor != null && anchor != null && anchor > 0.0) {
            rows = WorkingRows.withWorkingWeight(
                WorkingRows.cleared(working), ctx.steps.nearest(anchor * factor, ctx.equipment, ctx.unit),
                ctx.unit, ctx.equipment, rule.scheme,
            )
        }
        if (layoff is LayoffKind.Scaled && layoff.factor == 0.85) {
            val indices = WorkingRows.workSetIndices(rows)
            val keep = indices.take(ceil(indices.size * 2.0 / 3.0).toInt()).toSet()
            rows = rows.filterIndexed { i, row -> row.kind == SetKind.RAMP || i in keep }
        }
        if (ctx.light) rows = LightWeek.rows(rows, ctx)
        val ruleOverride = if (layoff == LayoffKind.Recalibrate && usesLoadCalibration(working)) {
            rule.copy(state = rule.state.copy(calibrating = true))
        } else null
        return Opening(rows, layoff, ruleOverride)
    }

    /** While a slot is calibrating: the weight to pre-fill on the next pending set. */
    fun nextSet(rule: SlotRule, logged: List<LoggedSet>, pending: LoggedSet, ctx: Context): LoggedSet {
        if (!rule.state.calibrating || pending.kind == SetKind.RAMP || pending.weightLb == 0.0 || pending.seconds > 0) return pending
        val anchors = logged.filter { it.done && (it.kind == SetKind.TOP || it.kind == SetKind.WORK) }
        val load = calibration(rule, anchors, ctx)?.first ?: return pending
        val weight = if (pending.kind == SetKind.BACKOFF) {
            GeneratedLoad.ramp(load * rule.scheme.backoffPct, ctx.unit, ctx.equipment)
        } else load
        return pending.copy(weightLb = weight)
    }

    /** After Finish: next time's working rows, the updated rule, and the one change with its reason.
     *  A reps-in-time main passes its [window] and takes [repsInTime] instead. */
    fun afterSession(
        rule: SlotRule,
        working: List<LoggedSet>,
        exposure: Exposure,
        ctx: Context,
        window: PrescribedTarget.RepsInTime? = null,
    ): Outcome {
        if (window != null) return repsInTime(rule, working, exposure, window, ctx)
        val work = WorkingRows.workSetIndices(exposure.planned)
        val done = work.mapNotNull { i -> exposure.actual.firstOrNull { it.plannedIndex == i && it.done }?.let { i to it } }
        if (done.isEmpty()) return Outcome(WorkingRows.cleared(working), rule, Change.Hold, Reason.NoSets)
        val sameShape = exposure.planned.map { it.kind } == working.map { it.kind }
        var base = if (exposure.layoff == LayoffKind.None && !ctx.light && sameShape) {
            WorkingRows.carried(exposure.planned, exposure.actual)
        } else WorkingRows.cleared(working)
        var current = rule.copy(state = rule.state.copy(exposures = rule.state.exposures + 1))
        fun result(reason: Reason, change: Change = Change.Hold, rows: List<LoggedSet> = base) =
            Outcome(rows, current, change, reason)
        fun loaded(load: Double): List<LoggedSet> =
            WorkingRows.withWorkingWeight(base, load, ctx.unit, ctx.equipment, current.scheme)

        val loadCalibration = usesLoadCalibration(working)
        val recalibrationRows = if (exposure.layoff == LayoffKind.Recalibrate && loadCalibration) {
            current = current.copy(state = current.state.copy(calibrating = true))
            val anchor = requireNotNull(WorkingRows.workingWeight(working))
            loaded(ctx.steps.nearest(anchor * 0.75, ctx.equipment, ctx.unit))
        } else null
        if (ctx.light) return result(Reason.LightCycle, Change.LightCycle, recalibrationRows ?: WorkingRows.cleared(working))
        if (current.state.calibrating && loadCalibration) {
            val anchors = done.map { it.second }.filter { it.kind == SetKind.TOP || it.kind == SetKind.WORK }
            val calibrated = calibration(rule, anchors, ctx)
            current = current.copy(state = current.state.resetStreaks().copy(calibrating = calibrated?.second != true))
            if (calibrated == null) return result(Reason.Calibrating, rows = recalibrationRows ?: WorkingRows.cleared(working))
            val (load, concluded) = calibrated
            return if (concluded) result(Reason.Calibrated, Change.Calibrated(load), loaded(load))
            else result(Reason.Calibrating, Change.Increase(anchors.last().weightLb, load), loaded(load))
        }
        if (exposure.layoff == LayoffKind.Hold || (exposure.layoff == LayoffKind.Recalibrate && !loadCalibration)) {
            return result(Reason.Layoff, rows = WorkingRows.cleared(working))
        }
        val anchorIndices = WorkingRows.workingIndices(exposure.planned)
        val doneAnchors = done.filter { it.first in anchorIndices }.map { it.second }
        val priorAnchor = requireNotNull(WorkingRows.workingWeight(working))
        val topIndex = exposure.planned.indexOfFirst { it.kind == SetKind.TOP }
        val votes = exposure.planned.indices.filter { exposure.planned[it].kind == SetKind.WORK }
        val carried = if (topIndex >= 0) done.firstOrNull { it.first == topIndex }?.second?.weightLb
        else done.filter { it.first in votes }.groupingBy { it.second.weightLb }.eachCount()
            .entries.singleOrNull { it.value > votes.size / 2 }?.key
        val loadChanged = exposure.layoff == LayoffKind.None && !ctx.light &&
            carried != null && carried != priorAnchor && priorAnchor > 0.0 &&
            working.none { it.seconds > 0 }
        if (topIndex >= 0 && exposure.layoff == LayoffKind.None) base = loaded(carried ?: priorAnchor)
        val lowerBlocked = done.any { (i, row) ->
            val planned = exposure.planned[i]
            planned.weightLb > 0.0 && planned.seconds == 0 && row.weightLb < planned.weightLb &&
                row.weightLb != carried && !(topIndex >= 0 && loadChanged && row.weightLb == base.getOrNull(i)?.weightLb)
        }
        if (loadChanged) {
            base = loaded(requireNotNull(carried))
            current = current.copy(state = current.state.resetStreaks())
            if (requireNotNull(carried) < priorAnchor && !lowerBlocked) {
                return result(Reason.Lowered, Change.KeptLowered(carried), base)
            }
        }
        val workingSets = WorkingRows.workSetIndices(working).map { working[it] }
        if (workingSets.any { it.seconds > 0 }) {
            val ceiling = ctx.holdCeilingSeconds
            return when {
                ceiling == null || workingSets.any { it.weightLb > 0.0 } -> result(Reason.Timed)
                exposure.layoff is LayoffKind.Scaled -> result(Reason.Layoff, rows = WorkingRows.cleared(working))
                else -> held(current, base, exposure, done, work.size, ceiling, ctx)
            }
        }
        val judgment = when {
            lowerBlocked || done.any { (i, row) -> row.reps < exposure.planned[i].reps } -> Judgment.REP_MISS
            done.size < work.size || carried == null -> Judgment.INCOMPLETE
            done.any { effectiveRir(it.second, rule) < rule.rir - 1 } -> Judgment.EFFORT_MISS
            else -> Judgment.SUCCESS
        }
        val layoff = exposure.layoff
        if (layoff is LayoffKind.Scaled) {
            if (judgment == Judgment.SUCCESS || judgment == Judgment.INCOMPLETE) {
                return result(Reason.Layoff, rows = WorkingRows.cleared(working))
            }
            current = current.copy(state = current.state.resetStreaks().copy(
                effortMissStreak = if (judgment == Judgment.EFFORT_MISS) 1 else 0,
            ))
            val load = requireNotNull(WorkingRows.workingWeight(exposure.planned))
            return result(Reason.Layoff, Change.Restart(layoff.factor), loaded(load))
        }
        val anchor = if (loadChanged) requireNotNull(carried) else priorAnchor
        val step = ctx.steps.stepLb(ctx.equipment)
        val bodyweight = workingSets.all { it.weightLb == 0.0 && it.seconds == 0 }
        val coarse = anchor > 0.0 && step / anchor > 0.10
        if (!bodyweight && coarse && current.target is RepTarget.Fixed) {
            val reps = current.target.reps
            current = current.copy(target = RepTarget.Range(reps, reps + 4, reps))
        }
        return when (judgment) {
            Judgment.SUCCESS -> success(current, base, done.map { it.second }, doneAnchors, anchor, step, bodyweight, coarse, ctx)
            Judgment.REP_MISS -> {
                val misses = current.state.repMissStreak + 1
                current = current.copy(state = current.state.copy(successStreak = 0, repMissStreak = misses, effortMissStreak = 0, pendingIncrease = false))
                if (misses < 2) return result(Reason.RepMiss)
                val recentStall = current.state.resetEpochDays.any { exposure.epochDay - it <= 42 }
                current = current.copy(
                    everyExposures = if (recentStall) minOf(3, current.everyExposures + 1) else current.everyExposures,
                    state = current.state.copy(repMissStreak = 0, resetEpochDays = (current.state.resetEpochDays + exposure.epochDay).sorted().takeLast(2)),
                )
                when {
                    current.dropPct == 0.0 -> result(Reason.Stall)
                    bodyweight || coarse -> {
                        val reps = maxOf(3, current.target.reps - 1)
                        val loweredTarget = when (val target = current.target) {
                            is RepTarget.Fixed -> RepTarget.Fixed(reps)
                            is RepTarget.Range -> target.copy(lo = minOf(target.lo, reps), current = reps)
                        }
                        current = current.copy(target = loweredTarget)
                        result(Reason.Stall, Change.RepsTo(reps), withReps(base, reps))
                    }
                    else -> {
                        val load = ctx.steps.down(anchor * (1 - current.dropPct), ctx.equipment, ctx.unit)
                        result(Reason.Stall, Change.Decrease(anchor, load), loaded(load))
                    }
                }
            }
            Judgment.INCOMPLETE -> {
                current = current.copy(state = current.state.copy(successStreak = 0, pendingIncrease = false))
                result(Reason.Incomplete)
            }
            Judgment.EFFORT_MISS -> {
                current = effortMissed(current)
                result(Reason.EffortMiss)
            }
        }
    }

    /** [measure] and [percent] are the day's resolved A16 settings ([CardioMeasures.resolve], the day's or plan's
     *  percent); [runCap] is the plan's Run cap setting, on by default. [sprint] is read on sprint days, which require it;
     *  [easy] opts an easy run into growth up to its session stop, once per rotation. */
    data class CardioContext(
        val todayEpochDay: Long,
        val measure: ProgressMeasure?,
        val percent: Int,
        val runCap: Boolean = true,
        val sprint: SprintContext? = null,
        val easy: EasyRunContext? = null,
        /** What the day's block may plan within the session minutes
         *  ([cloud.trotter.log.strength.domain.planner.SessionBudget.cardioBudget]); null skips the check. */
        val budgetSeconds: Int? = null,
        val ladder: LadderContext? = null,
    )

    /** The block a cardio day opens with, as a view: the taper from [goal]'s date, then the run cap against [recent].
     *  A ladder day opens unchanged: the ladder is its own guardrail.
     *  A sprint day takes neither: it opens its intro block after a layoff or the time trial on its rotation ([SprintRules]). */
    fun cardioAtStart(block: CardioBlock, rule: CardioRule?, recent: CardioHistory, goal: PlanGoal?, ctx: CardioContext): CardioBlock {
        if (rule?.ladderLevel != null) return block
        val session = rule?.session
        if (rule != null && session != null && session.sprint) {
            return SprintRules.opening(block, rule, session, requireNotNull(ctx.sprint))
        }
        val tapered = Taper.factor(goal, ctx.todayEpochDay)?.let { Taper.applied(block, it) } ?: block
        return if (ctx.runCap) RunCap.capped(tapered, RunCap.anchor(recent, ctx.todayEpochDay), recent.secondsPerMetre) else tapered
    }

    /** After Finish: next time's block and rule. A ladder day earns a credit on its first finished
     *  Easy session and moves up one level on the second ([RunWalkLadder.EASY_PER_LEVEL] per level); Good, no word or unfinished repeats and keeps the
     *  credit; Hard or Pain drops one and clears it. With [CardioContext.ladder], an Easy finish counts only while fewer
     *  than three counted finishes fall in the seven local days ending today and it is not dated before the newest stored
     *  ladder date; a level-up or LadderDone waits seven days after the last level-up while the runner is busy (design
     *  revision 47, practice).
     *  Other days take A16's increase on full success, capped against [recent] and this session.
     *  A ruled easy run with [CardioContext.easy] grows up to its stop once per rotation; Hard or Pain holds
     *  as practice (audit 2026-11-13 row 4). A long run grows up to its target; a quality session adds one unit
     *  of its range every two counted sessions (design §3.1). A sprint day steps every two counted sessions
     *  at most once per rotation ([SprintRules]). An alternate fits down through its range when it exceeds the minutes.
     *  If an alternate cannot fit at step 0, keep today's kind with no pending credit. */
    fun cardioAfterSession(
        block: CardioBlock,
        rule: CardioRule?,
        results: List<CardioSetResult>,
        effort: CardioEffort?,
        recent: CardioHistory,
        ctx: CardioContext,
        pain: Boolean = false,
    ): CardioOutcome {
        fun repeat() = CardioOutcome(block, rule, CardioChange.Repeat)
        val level = rule?.ladderLevel
        if (rule != null && level != null) {
            val finished = CardioMeasures.fullSuccess(block, results)
            val l = ctx.ladder
            fun counted() = l?.let { (rule.ladderCounted + it.todayEpochDay).takeLast(RunWalkLadder.COUNTED_PER_WEEK) }
                ?: rule.ladderCounted
            return when {
                pain || effort == CardioEffort.HARD -> {
                    val down = maxOf(1, level - 1)
                    val next = rule.copy(ladderLevel = down, credits = 0)
                    if (down == level) CardioOutcome(block, next, CardioChange.Repeat)
                    else CardioOutcome(RunWalkLadder.block(down, block.activity), next, CardioChange.LevelTo(down))
                }
                !finished || effort != CardioEffort.EASY -> repeat()
                l != null && l.todayEpochDay < maxOf(rule.ladderCounted.lastOrNull() ?: Long.MIN_VALUE,
                    rule.ladderRaised ?: Long.MIN_VALUE) -> repeat().copy(hold = LadderHold.BACKDATED)
                l != null && rule.ladderCounted.count { it in CardioHistory.weekEnding(l.todayEpochDay) } >=
                    RunWalkLadder.COUNTED_PER_WEEK -> repeat().copy(hold = LadderHold.WEEK_CAP)
                rule.credits + 1 < RunWalkLadder.EASY_PER_LEVEL -> CardioOutcome(block,
                    rule.copy(credits = rule.credits + 1, ladderCounted = counted()), CardioChange.Repeat)
                l != null && rule.ladderRaised != null && l.todayEpochDay - rule.ladderRaised < RunWalkLadder.LEVEL_UP_DAYS &&
                    l.busy -> repeat().copy(hold = LadderHold.BUSY)
                level == RunWalkLadder.TOP -> CardioOutcome(block, CardioRule(null), CardioChange.LadderDone)
                else -> CardioOutcome(RunWalkLadder.block(level + 1, block.activity),
                    rule.copy(ladderLevel = level + 1, credits = 0, ladderCounted = counted(), ladderRaised = l?.todayEpochDay),
                    CardioChange.LevelTo(level + 1))
            }
        }
        val session = rule?.session
        return when {
            rule == null || session == null -> grown(block, rule, results, recent, ctx) { null }
            session == RunSession.EASY -> {
                val easy = ctx.easy
                if (easy == null || pain || effort == CardioEffort.HARD || rule.stepCycle == easy.cyclesCompleted ||
                    block.sets.any { it.role == CardioRole.WORK && it.end !is Measure.Time }) return repeat()
                val next = grown(block, rule, results, recent, ctx) { easyStopped(it, easy) }
                if (next.change is CardioChange.Increase) next.copy(rule = rule.copy(stepCycle = easy.cyclesCompleted)) else next
            }
            session == RunSession.LONG -> grown(block, rule, results, recent, ctx) {
                longStopped(it, rule, recent.secondsPerMetre)
            }
            session.sprint -> SprintRules.afterSession(block, rule, session, results, effort, pain,
                requireNotNull(ctx.sprint), ctx.budgetSeconds)
            else -> ranged(block, rule, session, results, effort, recent, ctx)
        }
    }

    private fun grown(
        block: CardioBlock,
        rule: CardioRule?,
        results: List<CardioSetResult>,
        recent: CardioHistory,
        ctx: CardioContext,
        stop: (CardioSetSpec) -> Int?,
    ): CardioOutcome {
        fun repeat() = CardioOutcome(block, rule, CardioChange.Repeat)
        val measure = ctx.measure ?: return repeat()
        if (!CardioMeasures.fullSuccess(block, results)) return repeat()
        val increased = CardioMeasures.increased(block, measure, ctx.percent)
        val stopped = increased.copy(sets = increased.sets.mapIndexed { index, set ->
            if (set.role != CardioRole.WORK) return@mapIndexed set
            val limit = stop(set) ?: return@mapIndexed set
            when {
                limit <= block.sets[index].endValue() -> block.sets[index]
                set.endValue() <= limit -> set
                else -> set.withEndValue(limit)
            }
        })
        if (stopped == block) return repeat()
        val next = if (ctx.runCap) {
            RunCap.capped(stopped, capAnchor(recent, results), recent.secondsPerMetre)
        } else stopped
        return if (next == block) repeat()
        else CardioOutcome(next, rule, CardioChange.Increase(measure, capped = next != stopped))
    }

    private fun capAnchor(recent: CardioHistory, results: List<CardioSetResult>): RecentMaxima? {
        val session = RunCap.maxima(listOf(results))
        return if (session == null) recent.longest else recent.longest?.max(session) ?: session
    }

    private fun easyStopped(set: CardioSetSpec, easy: EasyRunContext): Int? = when (set.end) {
        is Measure.Time -> easy.stopSeconds
        is Measure.Distance -> null
    }

    private fun longStopped(set: CardioSetSpec, rule: CardioRule, secondsPerMetre: Double?): Int? = when (set.end) {
        is Measure.Time -> rule.longSeconds
        is Measure.Distance -> listOfNotNull(rule.longMetres, rule.longSeconds?.let { seconds ->
            secondsPerMetre?.let { ceil(seconds / it - 1e-9).toInt() }
        }).minOrNull()
    }

    private fun fits(next: CardioBlock, current: CardioBlock, ctx: CardioContext): Boolean =
        ctx.budgetSeconds == null || plannedSeconds(next) <= ctx.budgetSeconds ||
            plannedSeconds(next) <= plannedSeconds(current)

    private fun ranged(
        block: CardioBlock,
        rule: CardioRule,
        session: RunSession,
        results: List<CardioSetResult>,
        effort: CardioEffort?,
        recent: CardioHistory,
        ctx: CardioContext,
    ): CardioOutcome {
        val range = requireNotNull(RunRange.of(session))
        if (!CardioMeasures.fullSuccess(block, results) || (range.goodBand && effort == CardioEffort.HARD)) {
            return CardioOutcome(block, rule, CardioChange.Repeat)
        }
        val top = maxOf(range.top, rule.alternate?.let(RunRange::of)?.top ?: 0)
        val nextSession = rule.alternate ?: session
        val nextRange = requireNotNull(RunRange.of(nextSession))
        val work = block.sets.first { it.role == CardioRole.WORK }
        fun build(steps: Int): CardioBlock {
            val next = nextRange.block(minOf(steps, nextRange.top), block.activity)
            val built = if (nextSession != session || work.aim == null) next else next.copy(sets = next.sets.map { set ->
                if (set.role != CardioRole.WORK) set else set.copy(aim = work.withEndValue(set.endValue()).aim)
            })
            val first = block.sets.indexOfFirst { it.role == CardioRole.WORK || it.role == CardioRole.RECOVERY }
            val last = block.sets.indexOfLast { it.role == CardioRole.WORK || it.role == CardioRole.RECOVERY }
            return built.copy(sets = block.sets.take(first) + built.sets + block.sets.drop(last + 1))
        }
        val baseline = build(rule.steps)
        val shortOfRounds = nextSession == session && block.sets.count { it.role == CardioRole.WORK } <
            baseline.sets.count { it.role == CardioRole.WORK }
        val canStep = rule.steps < top || shortOfRounds
        val ready = rule.credits == 1 && canStep
        var steps = rule.steps
        var credits = if (!ready && canStep) 1 else 0
        var next = if (nextSession == session) block else baseline
        if (ready) {
            val stepped = if (shortOfRounds) {
                val recovery = block.sets.firstOrNull { it.role == CardioRole.RECOVERY }
                    ?: CardioSetSpec(CardioRole.RECOVERY, Measure.Time(range.recoverySeconds ?: work.endValue()))
                val last = block.sets.indexOfLast { it.role == CardioRole.WORK }
                block.copy(sets = block.sets.take(last + 1) + listOf(recovery, work) + block.sets.drop(last + 1))
            } else build(steps + 1)
            if (!fits(stepped, block, ctx) ||
                (ctx.runCap && RunCap.capped(stepped, capAnchor(recent, results), recent.secondsPerMetre) != stepped)) {
                credits = 1
            } else {
                next = stepped
                if (!shortOfRounds) steps += 1
            }
        }
        if (nextSession != session && !fits(next, block, ctx)) {
            val fittedSteps = (minOf(steps, nextRange.top) downTo 0).firstOrNull { fits(build(it), block, ctx) }
                ?: return CardioOutcome(block, rule.copy(credits = 0), CardioChange.Repeat)
            next = build(fittedSteps)
        }
        val change = when {
            nextSession != session -> CardioChange.Alternate(nextSession)
            steps != rule.steps || next != block -> CardioChange.Step
            else -> CardioChange.Repeat
        }
        return CardioOutcome(next,
            rule.copy(session = nextSession, alternate = rule.alternate?.let { session }, steps = steps, credits = credits), change)
    }

    private fun success(
        rule: SlotRule,
        base: List<LoggedSet>,
        done: List<LoggedSet>,
        anchors: List<LoggedSet>,
        anchor: Double,
        step: Double,
        bodyweight: Boolean,
        coarse: Boolean,
        ctx: Context,
    ): Outcome {
        val (cadenced, increaseDue) = cadence(rule, ctx)
        var current = cadenced
        if (!increaseDue) return Outcome(base, current, Change.Hold, Reason.Streak)
        var rows = base
        var change: Change = Change.Hold
        var reason: Reason = Reason.AllMade
        val target = current.target
        when {
            bodyweight -> {
                if (target.reps >= 20) return Outcome(base, current, Change.Hold, Reason.AddWeight)
                val reps = when (target) {
                    is RepTarget.Fixed -> minOf(20, target.reps + 1)
                    is RepTarget.Range -> minOf(20, target.hi, target.current + 1)
                }
                current = current.copy(target = target.atReps(reps))
                rows = withReps(base, reps)
                if (target.reps != 20) change = Change.RepsTo(reps)
                if (reps == 20) reason = Reason.AddWeight
            }
            target is RepTarget.Range && target.current < target.hi -> {
                val reps = minOf(target.hi, anchors.minOf { it.reps } + 1)
                current = current.copy(target = target.copy(current = reps))
                rows = withReps(base, reps)
                change = Change.RepsTo(reps)
            }
            target is RepTarget.Fixed || (target is RepTarget.Range && anchors.all { it.reps >= target.hi }) -> {
                if (done.any { effectiveRir(it, rule) == Effort.MAX.rir }) reason = Reason.MaxSet
                else {
                    val easy = done.all { effectiveRir(it, rule) >= Effort.EASY.rir }
                    val single = maxOf(1L, Math.round(anchor * rule.incrementPct / step))
                    val cap = maxOf(1L, floor(0.10 * anchor / step + 1e-9).toLong())
                    val doubled = anchor + minOf(2 * single, cap) * step
                    val doubleAllowed = easy && !coarse && done.maxBy { it.weightLb }.let { set ->
                        Estimate.e1rm(set.weightLb, set.reps, Effort.EASY.rir)?.let {
                            Estimate.rirAt(it, doubled, target.reps) >= rule.rir
                        } == true
                    }
                    val steps = if (coarse) 1L else minOf(if (doubleAllowed) 2 * single else single, cap)
                    val load = anchor + steps * step
                    rows = WorkingRows.withWorkingWeight(base, load, ctx.unit, ctx.equipment, rule.scheme)
                    if (target is RepTarget.Range) {
                        current = current.copy(target = target.copy(current = target.lo))
                        rows = withReps(rows, target.lo)
                    }
                    change = Change.Increase(anchor, load)
                    reason = if (doubleAllowed) Reason.EasySession else Reason.AllMade
                }
            }
        }
        return Outcome(rows, current, change, reason)
    }

    /** A bodyweight training hold (#643). A logged hold at or past its planned seconds is one success, however far
     *  past; at the cadence the target gains [HOLD_STEP_SECONDS], doubled when every hold was Easy, up to [ceiling].
     *  Misses keep the target. */
    private fun held(
        rule: SlotRule,
        base: List<LoggedSet>,
        exposure: Exposure,
        done: List<Pair<Int, LoggedSet>>,
        plannedHolds: Int,
        ceiling: Int,
        ctx: Context,
    ): Outcome {
        val unmet = rule.copy(state = rule.state.copy(successStreak = 0, pendingIncrease = false))
        if (done.any { (i, row) -> row.seconds < exposure.planned[i].seconds }) return Outcome(base, unmet, Change.Hold, Reason.RepMiss)
        if (done.size < plannedHolds) return Outcome(base, unmet, Change.Hold, Reason.Incomplete)
        val (current, increaseDue) = cadence(rule, ctx)
        if (!increaseDue) return Outcome(base, current, Change.Hold, Reason.Streak)
        val anchors = WorkingRows.workingIndices(base)
        val target = anchors.firstOrNull()?.let { base[it].seconds } ?: return Outcome(base, current, Change.Hold, Reason.Streak)
        if (target > ceiling) return Outcome(base, current, Change.Hold, Reason.Timed)
        if (target == ceiling) return Outcome(base, current, Change.Hold, Reason.HoldCeiling)
        val easy = done.all { Effort.of(it.second.rir) == Effort.EASY }
        val next = minOf(ceiling, target + HOLD_STEP_SECONDS * if (easy) 2 else 1)
        val rows = base.mapIndexed { i, row -> if (i in anchors) row.copy(seconds = next) else row }
        return Outcome(rows, current, Change.SecondsTo(next), Reason.HoldStep)
    }

    private enum class Judgment { SUCCESS, REP_MISS, INCOMPLETE, EFFORT_MISS }

    private fun usesLoadCalibration(rows: List<LoggedSet>): Boolean {
        val work = WorkingRows.workSetIndices(rows).map { rows[it] }
        return work.any { it.weightLb > 0.0 } && work.none { it.seconds > 0 }
    }

    private fun effectiveRir(set: LoggedSet, rule: SlotRule): Int = set.rir ?: rule.rir

    private fun SlotState.resetStreaks(): SlotState = copy(successStreak = 0, repMissStreak = 0, effortMissStreak = 0, pendingIncrease = false)

    private fun RepTarget.atReps(reps: Int): RepTarget = when (this) {
        is RepTarget.Fixed -> RepTarget.Fixed(reps)
        is RepTarget.Range -> copy(current = reps)
    }

    private fun withReps(rows: List<LoggedSet>, reps: Int): List<LoggedSet> {
        val anchors = WorkingRows.workingIndices(rows).toSet()
        return rows.mapIndexed { i, row -> if (i in anchors) row.copy(reps = reps) else row }
    }

    /** A reps-in-time set's aim this time: the working rows' reps once they carry one, else the authored aim; null when
     *  the prescription has no aim. */
    fun repAim(window: PrescribedTarget.RepsInTime, rows: List<LoggedSet>?): Int? {
        val authored = window.repAim ?: return null
        val working = rows?.let { WorkingRows.workingIndices(it).firstOrNull()?.let { i -> it[i].reps } }
        return working?.takeIf { it > 0 } ?: authored
    }

    /** The window stays fixed. The aim rises one rep when every window reaches it, including at Max, at the slot's
     *  cadence; any miss repeats it. Without an aim the set only measures.
     *  No rep ceiling, load, estimate or extra set (council 2026-11-23 Q2, #642). */
    private fun repsInTime(
        rule: SlotRule,
        working: List<LoggedSet>,
        exposure: Exposure,
        window: PrescribedTarget.RepsInTime,
        ctx: Context,
    ): Outcome {
        val work = WorkingRows.workSetIndices(exposure.planned)
        val done = work.mapNotNull { i -> exposure.actual.firstOrNull { it.plannedIndex == i && it.done } }
        val rows = WorkingRows.cleared(working)
        if (done.isEmpty()) return Outcome(rows, rule, Change.Hold, Reason.NoSets)
        val counted = rule.copy(state = rule.state.copy(exposures = rule.state.exposures + 1))
        val aim = repAim(window, exposure.planned)
            ?: return Outcome(withReps(rows, 0), counted, Change.Hold, Reason.Measured)
        if (ctx.light) return Outcome(rows, counted, Change.LightCycle, Reason.LightCycle)
        if (exposure.layoff != LayoffKind.None) return Outcome(rows, counted, Change.Hold, Reason.Layoff)
        fun held(next: SlotRule, reason: Reason) = Outcome(rows, next, Change.Hold, reason)
        return when {
            done.any { it.reps < aim } -> held(counted.copy(state = counted.state.resetStreaks()), Reason.RepMiss)
            done.size < work.size ->
                held(counted.copy(state = counted.state.copy(successStreak = 0, pendingIncrease = false)), Reason.Incomplete)
            else -> {
                val (cadenced, increaseDue) = cadence(counted, ctx)
                if (!increaseDue) held(cadenced, Reason.Streak)
                else Outcome(withReps(rows, aim + 1), cadenced.copy(target = RepTarget.Fixed(aim + 1)),
                    Change.RepsTo(aim + 1), Reason.AllMade)
            }
        }
    }

    /** One more success toward the slot's cadence: a pending shared increase starts a fresh streak, and a slowed cadence
     *  speeds back up after four in a row. Returns the rule and whether an increase is due now. */
    private fun cadence(rule: SlotRule, ctx: Context): Pair<SlotRule, Boolean> {
        var current = rule.copy(state = rule.state.resetStreaks().copy(
            successStreak = if (rule.state.pendingIncrease) 0 else rule.state.successStreak + 1,
        ))
        val increaseDue = rule.state.pendingIncrease || current.state.successStreak % current.everyExposures == 0
        if (current.everyExposures > ctx.baseCadence && current.state.successStreak >= 4) {
            current = current.copy(everyExposures = current.everyExposures - 1, state = current.state.copy(successStreak = 0))
        }
        return current to increaseDue
    }

    /** Two effort misses in a row slow the cadence by one exposure, to three at most. */
    private fun effortMissed(rule: SlotRule): SlotRule {
        val misses = rule.state.effortMissStreak + 1
        return rule.copy(
            everyExposures = if (misses >= 2) minOf(3, rule.everyExposures + 1) else rule.everyExposures,
            state = rule.state.copy(successStreak = 0, repMissStreak = 0, effortMissStreak = if (misses >= 2) 0 else misses,
                pendingIncrease = false),
        )
    }

    /** Easy is read as exactly 4 left so Epley preserves the slot's target RIR, subject to the one-step floor. */
    private fun calibration(rule: SlotRule, anchors: List<LoggedSet>, ctx: Context): Pair<Double, Boolean>? {
        val first = anchors.firstOrNull { effectiveRir(it, rule) < Effort.EASY.rir }
        if (first != null) {
            val estimate = Estimate.e1rm(first.weightLb, first.reps, effectiveRir(first, rule))
            val load = when {
                estimate != null -> ctx.steps.nearest(Estimate.loadFor(estimate, rule.target.reps, rule.rir), ctx.equipment, ctx.unit)
                first.reps >= rule.target.reps -> first.weightLb
                else -> ctx.steps.nearest(first.weightLb * 0.9, ctx.equipment, ctx.unit)
            }
            return load to true
        }
        val last = anchors.lastOrNull() ?: return null
        val floor = last.weightLb + ctx.steps.stepLb(ctx.equipment)
        val estimate = Estimate.e1rm(last.weightLb, last.reps, Effort.EASY.rir) ?: return floor to false
        val bound = Estimate.loadFor(estimate, rule.target.reps, rule.rir)
        return maxOf(ctx.steps.down(bound, ctx.equipment, ctx.unit), floor) to false
    }
}
