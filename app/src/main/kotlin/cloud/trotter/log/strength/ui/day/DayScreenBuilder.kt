package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.LastPerformed
import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalFormatter
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.PlateMath
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.util.Locale

/**
 * The pure §8.2 decision logic behind the day screen: set-row labels, the
 * pager focus and summary formats, the seeding-once
 * plan, and per-exercise focus. Kept free of Android and coroutines so
 * every behavior in the contract is unit-testable on the JVM; [DayViewModel] only
 * wires these to flows and repository writes.
 *
 * All weights arrive canonical (lb) and are converted to [unit] for display here
 * (SSOT via [WeightUnit]/[WeightStepper]); nothing downstream does lb/kg math.
 */
object DayScreenBuilder {
    fun headerTarget(exercise: ProgramExercise, generatedGoal: GoalTarget, unit: WeightUnit): HeaderTarget {
        val prescription = exercise.prescription
            ?: return HeaderTarget.Goal(GoalFormatter.label(generatedGoal, unit))
        fun load(lb: Double): String = "${WeightStepper.format(unit.fromLb(lb))} ${unit.name.lowercase(Locale.ROOT)}"
        fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        val display = when (val target = prescription.target) {
            is PrescribedTarget.LoadReps -> "${exercise.targetSets} × ${target.reps} · ${load(target.lb)}"
            is PrescribedTarget.Reps -> "${exercise.targetSets} × ${target.reps}"
            is PrescribedTarget.Hold -> "${exercise.targetSets} × ${clock(target.seconds)}" +
                if (target.addedLb > 0.0) " · ${load(target.addedLb)}" else ""
            is PrescribedTarget.RepsInTime -> clock(target.seconds)
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

    fun doneButtonState(doneSets: Int, totalSets: Int): DoneButtonState = when {
        doneSets <= 0 -> DoneButtonState.NOTHING_LOGGED
        totalSets > 0 && doneSets >= totalSets -> DoneButtonState.ALL_DONE
        else -> DoneButtonState.PARTIAL
    }

    fun focusIndex(rows: List<SetRowState>): Int = rows.indexOfFirst { !it.complete }

    fun focusLabel(kinds: List<SetKind>, index: Int): cloud.trotter.log.strength.ui.text.UiText.SetLabel {
        val kind = kinds.getOrElse(index) { SetKind.WORK }
        val number = if (kind == SetKind.RAMP) kinds.take(index + 1).count { it == SetKind.RAMP } else index + 1
        return cloud.trotter.log.strength.ui.text.UiText.SetLabel(kind, number)
    }

    fun currentPage(pageKeys: List<String>, selectedKey: String?, exercises: List<ExerciseCardState>): Int {
        if (selectedKey != null) pageKeys.indexOf(selectedKey).takeIf { it >= 0 }?.let { return it }
        val firstUndone = exercises.firstOrNull { it.rows.any { row -> !row.complete } }
        if (firstUndone == null && exercises.all { it.rows.isEmpty() }) return 0
        val fallback = firstUndone ?: exercises.lastOrNull()
        return fallback?.let { pageKeys.indexOf("exercise:${it.programExerciseId}").coerceAtLeast(0) } ?: 0
    }

    fun nextSetDisplay(rows: List<SetRowState>, focusIndex: Int, tracking: TrackingType, unit: WeightUnit): String? =
        if (focusIndex < 0) null else rows.getOrNull(focusIndex + 1)?.let {
            SetFormatter.summary(tracking, unit.toLb(it.weightDisplay), it.reps, it.seconds, unit, spaced = true)
        }

    /** One log write emitted by [seedPlan]. */
    data class SeedWrite(
        val programExerciseId: Long,
        val slot: String,
        val sets: List<LoggedSet>,
    )

    /** Complete unopened-main preview policy shared by Today and snapshots. */
    fun previewMainSets(slot: ProgramSlot, cfg: LifterConfig, catalog: ExerciseCatalog): List<LoggedSet> {
        slot.exercise.prescription?.let { return SetSeeder.seedPrescribed(slot.exercise.targetSets, it) }
        val entry = catalog.find(slot.exercise.exerciseId)
            // Preserve the authored count without inventing a load or prescription.
            ?: return List(slot.exercise.targetSets) { LoggedSet(0.0, 0, SetKind.WORK, done = false) }
        return SetSeeder.seed(slot.exercise, GoalCalculator.targetFor(entry, cfg), cfg)
    }

    /**
     * Which slots still need their ACTUAL log seeded from GOAL — every slot whose
     * (id, track) pair isn't already a key of [existing]. "Seeded once, then
     * persists": a slot with a stored log is never reseeded, so a changed GOAL
     * never rewrites a lifter's living record (spec principle 2).
     *
     * [existing] maps each stored track to its current row count, which the
     * partner path needs: a partner added to a lived-in slot must seed
     * row-aligned to the LIVE main track — the lifter may have added EXTRA sets
     * to it — not to the freshly computed seed.
     */
    fun seedPlan(
        slots: List<ProgramSlot>,
        existing: Map<Pair<Long, String>, Int>,
        cfg: LifterConfig,
        catalog: ExerciseCatalog,
    ): List<SeedWrite> {
        val writes = mutableListOf<SeedWrite>()
        for (slot in slots) {
            val pe = slot.exercise
            val mainSeed = pe.prescription?.let { SetSeeder.seedPrescribed(pe.targetSets, it) } ?: run {
                val entry = catalog.find(pe.exerciseId) ?: return@run null
                SetSeeder.seed(pe, GoalCalculator.targetFor(entry, cfg), cfg)
            } ?: continue
            if (slot.programExerciseId to Slot.MAIN !in existing) {
                writes += SeedWrite(slot.programExerciseId, Slot.MAIN, mainSeed)
            }
            val partner = pe.superset
            if (partner != null && slot.programExerciseId to Slot.SS !in existing) {
                val partnerEntry = catalog.find(partner.exerciseId) ?: continue
                val rows = existing[slot.programExerciseId to Slot.MAIN] ?: mainSeed.size
                writes += SeedWrite(
                    slot.programExerciseId,
                    Slot.SS,
                    SetSeeder.seedPartner(rows, GoalCalculator.targetFor(partnerEntry, cfg)),
                )
            }
        }
        return writes
    }

    /** Per-row kind labels: R1…, TOP, B/O, or a plain 1-based number for WORK/EXTRA. */
    fun kindLabels(sets: List<LoggedSet>): List<String> = kindLabelsForKinds(sets.map { it.kind })

    /**
     * The same per-row label rule as [kindLabels], taken straight from a list of
     * [SetKind] — the SSOT the Log screen's history grouping reuses instead of
     * re-deriving "R1/TOP/B/O/plain number" from [SessionSetEntity][
     * cloud.trotter.log.strength.data.db.entity.SessionSetEntity]'s stored kind
     * name.
     */
    fun kindLabelsForKinds(kinds: List<SetKind>): List<String> {
        var ramp = 0
        return kinds.mapIndexed { index, kind ->
            when (kind) {
                SetKind.RAMP -> "R${++ramp}"
                SetKind.TOP -> "TOP"
                SetKind.BACKOFF -> "B/O"
                SetKind.WORK, SetKind.EXTRA -> "${index + 1}"
            }
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

    /**
     * The "last time: …" chip's value (PLAN.md A1 bonus) — `null` when [last]
     * is `null` (the exercise has no prior completed performance), in which
     * case the card shows no chip at all. Formats by the logged VALUE
     * ([SetFormatter.summaryOfValues]), not the exercise's current tracking
     * type: a `session_set`/last-performed row can be legacy reps-shaped for
     * an exercise reclassified TIMED (history is never touched by the legacy
     * reps→seconds fixup, only live logs are).
     */
    fun lastTimeDisplay(last: LastPerformed?, unit: WeightUnit): String? =
        last?.let { SetFormatter.summaryOfValues(it.weightLb, it.reps, it.seconds, unit, spaced = true) }

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

    /**
     * The "Plates: …" line (docs/briefs/plate-math.md §2): the bar load for
     * the first undone MAIN-slot set — the one the lifter loads next, so it
     * tracks a ramp set by set. Silent (`null`) whenever there's nothing
     * useful to say: not a barbell exercise, every set is already done, or
     * [PlateMath.perSide] can't load the weight exactly. Superset partners
     * aren't considered (spec: main slot only in v1).
     */
    fun plateLine(main: List<LoggedSet>, equipment: List<Equipment>, unit: WeightUnit): String? {
        if (Equipment.BARBELL !in equipment) return null
        val next = main.firstOrNull { !it.done } ?: return null
        val perSide = PlateMath.perSide(unit.fromLb(next.weightLb), unit) ?: return null
        return perSide.joinToString(" + ") { WeightStepper.format(it) }.ifEmpty { "0" }
    }

    /** Card completion requires both tracks at each index; round counts still read MAIN alone. */
    fun allDone(main: List<LoggedSet>, partner: List<LoggedSet>? = null): Boolean =
        main.isNotEmpty() && main.withIndex().all { (index, set) ->
            set.done && (partner == null || partner.getOrNull(index)?.done == true)
        }

    private fun setSummary(set: LoggedSet, tracking: TrackingType, unit: WeightUnit): String =
        SetFormatter.summary(tracking, set.weightLb, set.reps, set.seconds, unit)

    private fun repWord(count: Int): String = if (count == 1) "REP" else "REPS"

    /** Half of [WeightStepper.format]'s last decimal place — the widest gap that
     *  can only be float noise once both sides are already at display
     *  precision. */
    private const val DISPLAY_EPSILON = 0.005
}
