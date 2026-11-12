package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.CardioJudge
import cloud.trotter.log.strength.data.LastPerformed
import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.SlotSession
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetLabel
import cloud.trotter.log.strength.domain.model.SetLabels
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.progression.distanceMetres
import cloud.trotter.log.strength.domain.progression.timeSeconds
import cloud.trotter.log.strength.domain.standards.GoalFormatter
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.PlateMath
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The pure §8.2 decision logic behind the day screen: set-row labels, the
 * workout selection and summary formats, and per-exercise focus. Kept free of
 * Android and coroutines so every behavior in the contract is unit-testable on
 * the JVM; [DayViewModel] only wires these to flows and repository writes.
 *
 * All weights arrive canonical (lb) and are converted to [unit] for display here
 * (SSOT via [WeightUnit]/[WeightStepper]); nothing downstream does lb/kg math.
 */
object DayScreenBuilder {
    fun headerTarget(exercise: ProgramExercise, generatedGoal: GoalTarget, unit: WeightUnit): HeaderTarget {
        val prescription = exercise.prescription
            ?: return HeaderTarget.Goal(GoalFormatter.label(generatedGoal, unit))
        fun load(lb: Double): String = "${WeightStepper.format(unit.fromLb(lb))} ${unit.name.lowercase(Locale.ROOT)}"
        val display = when (val target = prescription.target) {
            is PrescribedTarget.LoadReps -> "${exercise.targetSets} × ${target.reps} · ${load(target.lb)}"
            is PrescribedTarget.Reps -> "${exercise.targetSets} × ${target.reps}"
            is PrescribedTarget.Hold -> "${exercise.targetSets} × ${ClockText.minutesSeconds(target.seconds)}" +
                if (target.addedLb > 0.0) " · ${load(target.addedLb)}" else ""
            is PrescribedTarget.RepsInTime -> ClockText.minutesSeconds(target.seconds)
        }
        return HeaderTarget.Prescribed(
            display = display,
            repAim = (prescription.target as? PrescribedTarget.RepsInTime)?.repAim,
            eachSide = prescription.eachSide,
            resistance = prescription.resistance,
        )
    }

    /** Shared by the day header and the phone's watch projection; excludes "Target ". */
    fun headerTargetLabel(
        target: HeaderTarget,
        aim: (String, Int) -> String = { display, reps -> "$display · aim $reps" },
    ): String = if (target is HeaderTarget.Prescribed && target.repAim != null) {
        aim(target.display, target.repAim)
    } else target.display

    /** Authored supporting detail only; the last performance belongs to the phone. */
    fun headerTargetDetail(target: HeaderTarget, eachSideLabel: String = "Each side"): String =
        listOfNotNull(
            eachSideLabel.takeIf { target is HeaderTarget.Prescribed && target.eachSide },
            (target as? HeaderTarget.Prescribed)?.resistance?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    fun watchStatusLine(
        lastContactMillis: Long,
        syncingChanges: Int,
        queuedChanges: Int,
        watchConnected: Boolean,
        nowMillis: Long,
    ): WatchStatus? = when {
        syncingChanges > 0 -> WatchStatus(WatchStatusKind.SYNCING, syncingChanges)
        queuedChanges > 0 && !watchConnected -> WatchStatus(WatchStatusKind.OFFLINE_QUEUED, queuedChanges)
        lastContactMillis > 0L && nowMillis - lastContactMillis <= WATCH_ACTIVE_WINDOW_MS -> WatchStatus(WatchStatusKind.ACTIVE)
        else -> null
    }

    fun markRemoteTick(state: DayUiState, tick: cloud.trotter.log.strength.sync.RemoteTick?): DayUiState {
        if (tick == null || tick.dayId != state.viewDayId) return state.copy(remoteTick = tick)
        return state.copy(
            remoteTick = tick,
            exercises = state.exercises.map { card ->
                if (card.programExerciseId != tick.programExerciseId) card else card.copy(
                    rows = card.rows.map { row ->
                        row.copy(
                            justTickedRemotely = row.index == tick.setIndex,
                            remoteTickEventId = if (row.index == tick.setIndex) tick.eventId else 0L,
                        )
                    },
                )
            },
        )
    }

    private const val WATCH_ACTIVE_WINDOW_MS = 2 * 60 * 1000L

    fun focusIndex(rows: List<SetRowState>): Int = rows.indexOfFirst { !it.complete }

    fun allDone(rows: List<SetRowState>): Boolean = allDone(rows.count { it.complete }, rows.size)

    fun allDone(done: Int, available: Int): Boolean = available > 0 && done >= available

    fun currentPage(pageKeys: List<String>, selectedKey: String?, exercises: List<ExerciseCardState>): Int {
        if (selectedKey != null) pageKeys.indexOf(selectedKey).takeIf { it >= 0 }?.let { return it }
        val firstUndone = exercises.firstOrNull { it.rows.any { row -> !row.complete } }
        if (firstUndone == null && exercises.all { it.rows.isEmpty() }) return 0
        val fallback = firstUndone ?: exercises.lastOrNull()
        return fallback?.let { pageKeys.indexOf("exercise:${it.programExerciseId}").coerceAtLeast(0) } ?: 0
    }

    /** Per-row kind labels: R1…, TOP, B/O, or a plain 1-based number for WORK/EXTRA. */
    fun kindLabels(sets: List<LoggedSet>): List<String> = kindLabelsForKinds(sets.map { it.kind })

    /** Phone rendering of [SetLabels], also used by the Log screen's history grouping. */
    fun kindLabelsForKinds(kinds: List<SetKind>): List<String> = SetLabels.of(kinds).map { label ->
        when (label) {
            is SetLabel.Ramp -> "R${label.number}"
            SetLabel.Top -> "TOP"
            SetLabel.Backoff -> "B/O"
            is SetLabel.Ordinal -> "${label.number}"
        }
    }

    /** Every recorded prescription, including unfinished rows; SetFormatter owns the units. */
    fun summaryLine(
        main: List<LoggedSet>,
        partner: List<LoggedSet>?,
        tracking: TrackingType,
        partnerTracking: TrackingType,
        unit: WeightUnit,
    ): String = main.indices.joinToString(", ") { index ->
        val mainText = setSummary(main[index], tracking, unit)
        partner?.getOrNull(index)?.let { "$mainText(${setSummary(it, partnerTracking, unit)})" } ?: mainText
    }

    fun workingHeading(rows: List<LoggedSet>, tracking: TrackingType, unit: WeightUnit): String? {
        val working = WorkingRows.workingIndices(rows).map(rows::get)
        val first = working.firstOrNull() ?: return null
        val uniform = when (tracking) {
            TrackingType.WEIGHTED -> working.all { it.weightLb == first.weightLb && it.reps == first.reps }
            TrackingType.REPS -> working.all { it.reps == first.reps }
            TrackingType.TIMED -> working.all { it.seconds == first.seconds && it.weightLb == first.weightLb }
        }
        if (!uniform) return working.joinToString(", ") {
            SetFormatter.summary(tracking, it.weightLb, it.reps, it.seconds, unit, spaced = true)
        }
        return when (tracking) {
            TrackingType.WEIGHTED -> {
                val count = if (first.kind == SetKind.TOP) "TOP" else "${working.size}"
                "$count × ${first.reps} · ${SetFormatter.weight(first.weightLb, unit)}"
            }
            TrackingType.REPS -> "${working.size} × ${first.reps}"
            TrackingType.TIMED -> "${working.size} × ${ClockText.minutesSeconds(first.seconds)}" +
                if (first.weightLb > 0.0) " · ${SetFormatter.weight(first.weightLb, unit)}" else ""
        }
    }

    /** The newest session of this day owns the offer; row edits can hide it without rewriting history. */
    fun nextTime(
        rows: List<LoggedSet>, history: SlotSession?, keepToPlan: Boolean, unit: WeightUnit,
        offersOn: Boolean, rule: SlotRule? = null,
    ): NextTime? {
        if (!offersOn || keepToPlan || rows.any { it.done }) return null
        val local = history?.takeUnless { it.retired || it.rough } ?: return null
        if (local.offer == null && local.opened) return null
        val current = WorkingRows.workingWeight(rows) ?: return null
        fun display(lb: Double) = WeightStepper.toDisplayPrecision(unit.fromLb(lb))
        val offer = local.offer ?: return NextTime.Repeat.takeIf {
            WorkingRows.workingWeight(local.planned)?.let { display(it) == display(current) } == true
        }
        val currentReps = WorkingRows.workingIndices(rows).firstOrNull()?.let { rows[it].reps }
        val applied = when {
            offer.calibration && offer.fromLb == offer.toLb && rule?.state?.calibrating == false -> true
            display(current) == display(offer.fromLb) && (offer.fromReps == null || currentReps == offer.fromReps) -> false
            display(current) == display(offer.toLb) && (offer.toReps == null || currentReps == offer.toReps) -> true
            else -> return null
        }
        return NextTime.Offer(if (offer.calibration) SetFormatter.weight(offer.toLb, unit) else offerLabel(offer.toLb, offer.fromLb, unit), applied, offer.toLb, offer.fromLb,
            offer.toReps.takeIf { display(offer.fromLb) == display(offer.toLb) }, offer.calibration)
    }

    /** [CardioJudge.opening] at [nowMillis], on the civil day [loggedToday] reads. Read-only: a run freezes it. */
    fun cardioOpening(day: ProgramDay, plan: PlanEntity, sessions: List<CardioSessionEntity>,
                      sets: Map<Long, List<CardioSetResult>>, nowMillis: Long): CardioBlock? =
        CardioJudge.opening(day, plan, sessions, sets, nowMillis,
            Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay())

    /** The cardio chip: [CardioJudge]'s outcome for the day's newest session in [sessions], selected while the day
     *  holds it, unselected while the day holds the session's opening. The plan's offers switch controls visibility
     *  in both Automatic and Ask modes. */
    fun cardioNextTime(day: ProgramDay, plan: PlanEntity, sessions: List<CardioSessionEntity>,
                       sets: Map<Long, List<CardioSetResult>>): CardioNextTime? {
        if (!plan.offerCardioIncreases) return null
        val stored = day.cardioToRun ?: return null
        val last = sessions.filter { it.planId == plan.id && it.dayId == day.id }
            .maxWithOrNull(compareBy({ it.completedAt }, { it.id })) ?: return null
        val judged = CardioJudge.judge(day, last, sessions, sets) ?: return null
        val opening = judged.opening
        val change = judged.outcome.change as? CardioChange.Increase
            ?: return CardioNextTime.Repeat.takeIf { stored == opening }
        val offered = judged.outcome.block
        val applied = when (stored) {
            opening -> false
            offered -> true
            else -> return null
        }
        return CardioNextTime.Offer(offered, opening, applied, cardioIncrease(opening, offered, change.measure))
    }

    private fun cardioIncrease(opening: CardioBlock, offered: CardioBlock, measure: ProgressMeasure): CardioIncrease {
        val first = opening.sets.indexOfFirst { it.role == CardioRole.WORK }
        val before = opening.sets[first]
        val after = offered.sets[first]
        return when (measure) {
            ProgressMeasure.DISTANCE -> CardioIncrease.Distance(after.distanceMetres() - before.distanceMetres())
            ProgressMeasure.TIME -> CardioIncrease.Time(after.timeSeconds() - before.timeSeconds())
            ProgressMeasure.PACE -> when (before.end) {
                is Measure.Distance -> CardioIncrease.Faster(before.timeSeconds() - after.timeSeconds())
                is Measure.Time -> CardioIncrease.Distance(after.distanceMetres() - before.distanceMetres())
            }
        }
    }

    fun offerDelta(offerLb: Double, openingLb: Double, unit: WeightUnit): Double =
        WeightStepper.toDisplayPrecision(unit.fromLb(offerLb)) - WeightStepper.toDisplayPrecision(unit.fromLb(openingLb))

    fun offerLabel(offerLb: Double, openingLb: Double, unit: WeightUnit): String {
        val delta = offerDelta(offerLb, openingLb, unit)
        return "${if (delta < 0) "−" else "+"}${WeightStepper.format(kotlin.math.abs(delta))} ${unit.name.lowercase(Locale.ROOT)}"
    }

    /**
     * Every working set from the last session, or the single [last] performance
     * when no working sets were done. Null when neither exists. Formats by the
     * logged values, since archived rows can predate a tracking-type change.
     */
    fun lastTimeDisplay(last: LastPerformed?, sets: List<LoggedSet>, unit: WeightUnit): String? =
        if (sets.isNotEmpty()) SetFormatter.performed(sets, unit)
        else last?.let { SetFormatter.summaryOfValues(it.weightLb, it.reps, it.seconds, unit, spaced = true) }

    /**
     * The "Best: …" chip's value (docs/briefs/performance-profile.md Phase 1)
     * — `null` when [record] is `null` (never performed), and also `null`
     * when it formats identically to [lastTime]'s chip: the two lines sit
     * right next to each other, so a record that IS the last performance
     * would just repeat the same number — quiet redundancy, not signal.
     * Value-formatted for the same legacy-history reason as [lastTimeDisplay].
     */
    fun personalRecordDisplay(record: PersonalRecord?, lastTime: LastPerformed?, unit: WeightUnit): String? {
        val display = record?.let { SetFormatter.summaryOfValues(it.weightLb, it.reps, it.seconds, unit) } ?: return null
        val lastDisplay = lastTime?.let { SetFormatter.summaryOfValues(it.weightLb, it.reps, it.seconds, unit) }
        return display.takeIf { it != lastDisplay }
    }

    /**
     * The TOP set's one-line comparison against the last time this exercise was
     * performed: "+5 LB FROM LAST", "MATCHED", "FIRST LOG". Derived from what
     * the card already holds — [main] is the live log the rows render from and
     * [last] is the read [lastTimeDisplay] already made — so narrating the
     * number costs no extra query.
     *
     * Keyed to [SetKind.TOP], which is why it is silent on REPS and TIMED
     * exercises: [cloud.trotter.log.strength.domain.seeding.SetSeeder] gives
     * those all-WORK rows and no TOP, so there is no single set that carries the
     * day's intent for them. It is silent for the same honesty reason when
     * [last]'s own values aren't weight-shaped ([SetFormatter.trackingOfValues])
     * — a legacy reps-shaped history row can't be subtracted from a load.
     *
     * The delta is taken between the two weights *as the screen prints them*
     * ([WeightStepper.toDisplayPrecision]), in the display unit. Subtracting the
     * raw converted values instead would let a kg card narrate "+0.01 KG FROM
     * LAST" between two loads that both read 45.36 — narration that contradicts
     * the numbers it sits under is worse than no narration.
     */
    fun topSetComparison(main: List<LoggedSet>, last: LastPerformed?, unit: WeightUnit): String? {
        val top = main.firstOrNull { it.kind == SetKind.TOP } ?: return null
        if (last == null) return "FIRST LOG"
        if (SetFormatter.trackingOfValues(last.weightLb, last.reps, last.seconds) != TrackingType.WEIGHTED) {
            return null
        }
        val topWeight = WeightStepper.toDisplayPrecision(unit.fromLb(top.weightLb))
        val lastWeight = WeightStepper.toDisplayPrecision(unit.fromLb(last.weightLb))
        val delta = topWeight - lastWeight
        // Equal on screen is equal, and the reps below decide what to say about
        // it. The epsilon only absorbs the float noise of subtracting two
        // already-rounded values — a real difference is at least one hundredth.
        if (delta > DISPLAY_EPSILON) return "+${WeightStepper.format(delta)} ${unit.name} FROM LAST"
        if (delta < -DISPLAY_EPSILON) return "−${WeightStepper.format(-delta)} ${unit.name} FROM LAST"
        val reps = top.reps - last.reps
        return when {
            reps > 0 -> "+$reps ${repWord(reps)} FROM LAST"
            reps < 0 -> "−${-reps} ${repWord(-reps)} FROM LAST"
            else -> "MATCHED"
        }
    }

    /**
     * The ADD WEIGHT / REMOVE WEIGHT pill for [entry] (§4.2): derived, never
     * invented — a loaded variant ([ExerciseEntry.weightedPairId]) yields
     * "ADD WEIGHT" targeting it; being the loaded target of some other entry
     * ([ExerciseCatalog.bodyweightPairFor]) yields "REMOVE WEIGHT" targeting
     * that unloaded entry. An entry can only ever be one side of a pair (the
     * library validates this is injective/acyclic at init), so at most one of
     * the two resolves. `null` when [entry] has no pair link at all, or is
     * `null` itself (an unknown exercise id).
     */
    fun weightSwapAffordance(entry: ExerciseEntry?, catalog: ExerciseCatalog): WeightSwapAffordance? {
        entry ?: return null
        entry.weightedPairId?.let { targetId ->
            val target = catalog.find(targetId) ?: return null
            return WeightSwapAffordance(targetId, target.name, isRemove = false)
        }
        val bodyweightId = catalog.bodyweightPairFor(entry.id) ?: return null
        val target = catalog.find(bodyweightId) ?: return null
        return WeightSwapAffordance(bodyweightId, target.name, isRemove = true)
    }

    /** Exact per-side plates for the displayed set; "0" denotes an empty bar. */
    fun plateLine(
        main: List<LoggedSet>,
        equipment: List<Equipment>,
        unit: WeightUnit,
        tracking: TrackingType,
        focusedIndex: Int,
    ): String? {
        if (tracking != TrackingType.WEIGHTED || Equipment.BARBELL !in equipment) return null
        val focused = main.getOrNull(focusedIndex)?.takeIf { it.weightLb > 0.0 } ?: return null
        val perSide = PlateMath.perSide(unit.fromLb(focused.weightLb), unit) ?: return null
        return perSide.joinToString(" + ") { WeightStepper.format(it) }.ifEmpty { "0" }
    }

    private fun setSummary(set: LoggedSet, tracking: TrackingType, unit: WeightUnit): String =
        SetFormatter.summary(tracking, set.weightLb, set.reps, set.seconds, unit)

    private fun repWord(count: Int): String = if (count == 1) "REP" else "REPS"

    /** Half of [WeightStepper.format]'s last decimal place — the widest gap that
     *  can only be float noise once both sides are already at display
     *  precision. */
    private const val DISPLAY_EPSILON = 0.005
}
