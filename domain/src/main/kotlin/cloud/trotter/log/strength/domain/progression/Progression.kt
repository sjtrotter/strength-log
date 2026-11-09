package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.GeneratedLoad
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.math.ceil
import kotlin.math.floor

/** The log to next targets for a lift slot. Pure: callers pass the slot's stored rows and rule and write back
 *  [Outcome.rows] and [Outcome.rule] together. */
object Progression {
    /** [planned] is the run's opening view; actual rows identify it through plannedIndex. */
    data class Exposure(
        val planned: List<LoggedSet>,
        val actual: List<LoggedSet>,
        val epochDay: Long,
        val roughDay: Boolean,
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
    )

    /** ceil(7 × training entries ÷ (days a week × times the exercise appears per rotation)). */
    fun expectedRecurrenceDays(trainingEntries: Int, daysPerWeek: Int, appearancesPerRotation: Int): Int =
        ceil(7.0 * trainingEntries / (daysPerWeek * appearancesPerRotation)).toInt()

    /** Rows a run opens with: layoff scaling and the light cycle applied as a view. Nothing is written back.
     *  [lastTrainedEpochDay] is the exercise's last session anywhere in the plan; [expectedRecurrenceDays] comes from the plan. */
    fun atStart(
        rule: SlotRule,
        working: List<LoggedSet>,
        lastTrainedEpochDay: Long?,
        expectedRecurrenceDays: Int,
        ctx: Context,
    ): Opening {
        val layoffDays = lastTrainedEpochDay?.let { ctx.todayEpochDay - it - expectedRecurrenceDays } ?: 0L
        val layoff = when {
            layoffDays <= 3 -> LayoffKind.None
            layoffDays <= 7 -> LayoffKind.Hold
            layoffDays <= 14 -> LayoffKind.Scaled(0.95)
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
        val ruleOverride = if (layoff == LayoffKind.Recalibrate) rule.copy(state = rule.state.copy(calibrating = true)) else null
        return Opening(rows, layoff, ruleOverride)
    }

    /** While a slot is calibrating: the weight to pre-fill on the next pending set. */
    fun nextSet(rule: SlotRule, logged: List<LoggedSet>, pending: LoggedSet, ctx: Context): LoggedSet {
        if (!rule.state.calibrating || pending.kind == SetKind.RAMP) return pending
        val anchors = logged.filter { it.done && (it.kind == SetKind.TOP || it.kind == SetKind.WORK) }
        val load = calibration(rule, anchors, ctx)?.first ?: return pending
        val weight = if (pending.kind == SetKind.BACKOFF) {
            GeneratedLoad.ramp(load * rule.scheme.backoffPct, ctx.unit, ctx.equipment)
        } else load
        return pending.copy(weightLb = weight)
    }

    /** After Finish: next time's working rows, the updated rule, and the one change with its reason. */
    fun afterSession(rule: SlotRule, working: List<LoggedSet>, exposure: Exposure, ctx: Context): Outcome {
        val work = WorkingRows.workSetIndices(exposure.planned)
        val done = work.mapNotNull { i -> exposure.actual.firstOrNull { it.plannedIndex == i && it.done }?.let { i to it } }
        if (done.isEmpty()) return Outcome(WorkingRows.cleared(working), rule, Change.Hold, Reason.NoSets)
        val sameShape = exposure.planned.map { it.kind } == working.map { it.kind }
        val base = if (exposure.layoff == LayoffKind.None && !ctx.light && sameShape) {
            WorkingRows.carried(exposure.planned, exposure.actual)
        } else WorkingRows.cleared(working)
        var current = rule.copy(state = rule.state.copy(exposures = rule.state.exposures + 1))
        fun result(reason: Reason, change: Change = Change.Hold, rows: List<LoggedSet> = base) = Outcome(rows, current, change, reason)
        fun loaded(load: Double): List<LoggedSet> =
            WorkingRows.withWorkingWeight(base, load, ctx.unit, ctx.equipment, current.scheme)

        if (exposure.roughDay) return result(Reason.RoughDay)
        if (ctx.light) return result(Reason.LightCycle, Change.LightCycle, WorkingRows.cleared(working))
        if (rule.state.calibrating || exposure.layoff == LayoffKind.Recalibrate) {
            val anchors = done.map { it.second }.filter { it.kind == SetKind.TOP || it.kind == SetKind.WORK }
            val calibrated = calibration(rule, anchors, ctx)
            current = current.copy(state = current.state.resetStreaks().copy(calibrating = calibrated?.second != true))
            if (calibrated == null) return result(Reason.Calibrating, rows = WorkingRows.cleared(working))
            val (load, concluded) = calibrated
            return if (concluded) result(Reason.Calibrated, Change.Calibrated(load), loaded(load))
            else result(Reason.Calibrating, Change.Increase(anchors.last().weightLb, load), loaded(load))
        }
        if (exposure.layoff == LayoffKind.Hold) return result(Reason.Layoff, rows = WorkingRows.cleared(working))
        val anchorIndices = WorkingRows.workingIndices(exposure.planned)
        val doneAnchors = done.filter { it.first in anchorIndices }.map { it.second }
        val lowered = done.filter { (i, row) -> i in anchorIndices && row.weightLb < exposure.planned[i].weightLb }
        if (lowered.isNotEmpty() && done.none { (i, row) -> row.weightLb > exposure.planned[i].weightLb }) {
            val load = lowered.minOf { it.second.weightLb }
            current = current.copy(state = current.state.resetStreaks())
            return result(Reason.Lowered, Change.KeptLowered(load), loaded(load))
        }
        val workingSets = WorkingRows.workSetIndices(working).map { working[it] }
        if (workingSets.any { it.seconds > 0 }) return result(Reason.Timed)
        val judgment = when {
            done.any { (i, row) -> row.reps < exposure.planned[i].reps } -> Judgment.REP_MISS
            done.size < work.size -> Judgment.INCOMPLETE
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
        val anchor = requireNotNull(WorkingRows.workingWeight(working))
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
                current = current.copy(state = current.state.copy(successStreak = 0, repMissStreak = misses, effortMissStreak = 0))
                if (misses < 2) return result(Reason.RepMiss)
                val recentStall = current.state.resetEpochDays.any { exposure.epochDay - it <= 42 }
                current = current.copy(
                    everyExposures = if (recentStall) minOf(3, current.everyExposures + 1) else current.everyExposures,
                    state = current.state.copy(repMissStreak = 0, resetEpochDays = (current.state.resetEpochDays + exposure.epochDay).takeLast(2)),
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
                current = current.copy(state = current.state.copy(successStreak = 0))
                result(Reason.Incomplete)
            }
            Judgment.EFFORT_MISS -> {
                val misses = current.state.effortMissStreak + 1
                current = current.copy(
                    everyExposures = if (misses >= 2) minOf(3, current.everyExposures + 1) else current.everyExposures,
                    state = current.state.copy(successStreak = 0, repMissStreak = 0, effortMissStreak = if (misses >= 2) 0 else misses),
                )
                result(Reason.EffortMiss)
            }
        }
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
        var current = rule.copy(state = rule.state.resetStreaks().copy(successStreak = rule.state.successStreak + 1))
        if (current.state.successStreak % current.everyExposures != 0) return Outcome(base, current, Change.Hold, Reason.Streak)
        var rows = base
        var change: Change = Change.Hold
        var reason: Reason = Reason.AllMade
        val target = current.target
        when {
            bodyweight -> {
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
                    val steps = if (coarse) 1L else minOf(if (easy) 2 * single else single, cap)
                    val load = anchor + steps * step
                    rows = WorkingRows.withWorkingWeight(base, load, ctx.unit, ctx.equipment, rule.scheme)
                    if (target is RepTarget.Range) {
                        current = current.copy(target = target.copy(current = target.lo))
                        rows = withReps(rows, target.lo)
                    }
                    change = Change.Increase(anchor, load)
                    reason = if (easy) Reason.EasySession else Reason.AllMade
                }
            }
        }
        if (current.everyExposures > ctx.baseCadence && current.state.successStreak >= 4) {
            current = current.copy(everyExposures = current.everyExposures - 1, state = current.state.copy(successStreak = 0))
        }
        return Outcome(rows, current, change, reason)
    }

    private enum class Judgment { SUCCESS, REP_MISS, INCOMPLETE, EFFORT_MISS }

    private fun effectiveRir(set: LoggedSet, rule: SlotRule): Int = set.rir ?: rule.rir

    private fun SlotState.resetStreaks(): SlotState = copy(successStreak = 0, repMissStreak = 0, effortMissStreak = 0)

    private fun RepTarget.atReps(reps: Int): RepTarget = when (this) {
        is RepTarget.Fixed -> RepTarget.Fixed(reps)
        is RepTarget.Range -> copy(current = reps)
    }

    private fun withReps(rows: List<LoggedSet>, reps: Int): List<LoggedSet> {
        val anchors = WorkingRows.workingIndices(rows).toSet()
        return rows.mapIndexed { i, row -> if (i in anchors) row.copy(reps = reps) else row }
    }

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
        return maxOf(
            ctx.steps.up(last.weightLb * 1.25, ctx.equipment, ctx.unit),
            last.weightLb + 2 * ctx.steps.stepLb(ctx.equipment),
        ) to false
    }
}
