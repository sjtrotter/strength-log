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
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.PlateMath
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.today.TodayScreenBuilder

/**
 * The pure §8.2 decision logic behind the day screen: set-row labels, the
 * collapsed-summary formats, auto/manual collapse resolution, the seeding-once
 * plan, and the one-tick-per-round rule. Kept free of Android and coroutines so
 * every behavior in the contract is unit-testable on the JVM; [DayViewModel] only
 * wires these to flows and repository writes.
 *
 * All weights arrive canonical (lb) and are converted to [unit] for display here
 * (SSOT via [WeightUnit]/[WeightStepper]); nothing downstream does lb/kg math.
 */
object DayScreenBuilder {

    /** Marks exactly the first undone row in the first unfinished card. */
    fun markNext(cards: List<ExerciseCardState>): List<ExerciseCardState> {
        val nextCardIndex = cards.indexOfFirst { card -> card.rows.any { !it.done } }
        return cards.mapIndexed { cardIndex, card ->
            val nextRowIndex = if (cardIndex == nextCardIndex) card.rows.indexOfFirst { !it.done } else -1
            card.copy(rows = card.rows.mapIndexed { rowIndex, row -> row.copy(isNext = rowIndex == nextRowIndex) })
        }
    }

    /** One log write emitted by [seedPlan]. */
    data class SeedWrite(
        val programExerciseId: Long,
        val slot: String,
        val sets: List<LoggedSet>,
    )

    /** Complete unopened-main preview policy shared by Today and snapshots. */
    fun previewMainSets(slot: ProgramSlot, cfg: LifterConfig, catalog: ExerciseCatalog): List<LoggedSet> {
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
     * partner path needs: a partner added to a lived-in slot (#93) must seed
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
            val entry = catalog.find(pe.exerciseId) ?: continue
            val mainSeed = SetSeeder.seed(pe, GoalCalculator.targetFor(entry, cfg), cfg)
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

    /**
     * Applies a round's done tick. For a superset both tracks flip together (one
     * tick per round, performed back-to-back); [partner] is null for a plain
     * exercise (spec §8.2). Returns the updated tracks.
     */
    fun applyRoundTick(
        main: List<LoggedSet>,
        partner: List<LoggedSet>?,
        index: Int,
        checked: Boolean,
    ): Pair<List<LoggedSet>, List<LoggedSet>?> {
        val newMain = main.mapIndexed { i, s -> if (i == index) s.copy(done = checked) else s }
        val newPartner = partner?.mapIndexed { i, s -> if (i == index) s.copy(done = checked) else s }
        return newMain to newPartner
    }

    /** Per-row kind labels: R1…, TOP, B/O, or a plain 1-based number for WORK/EXTRA. */
    fun kindLabels(sets: List<LoggedSet>): List<String> = kindLabelsForKinds(sets.map { it.kind })

    /**
     * The same per-row label rule as [kindLabels], taken straight from a list of
     * [SetKind] — the SSOT the Log screen's history grouping (#14) reuses instead
     * of re-deriving "R1/TOP/B/O/plain number" from [SessionSetEntity][
     * cloud.trotter.log.strength.data.db.entity.SessionSetEntity]'s stored
     * kind name.
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

    /**
     * The collapsed one-line summary (spec §8.2): completed rows formatted per
     * [tracking]/[partnerTracking] (SSOT: [SetFormatter], never an ad-hoc
     * `w×r`), joined by " · ", or `main(partner)` joined by " / " for a
     * superset. When nothing is checked yet, `{n} sets · GOAL {g}`.
     */
    fun collapsedSummary(
        main: List<LoggedSet>,
        partner: List<LoggedSet>?,
        goalDisplay: String,
        unit: WeightUnit,
        tracking: TrackingType = TrackingType.WEIGHTED,
        partnerTracking: TrackingType = TrackingType.WEIGHTED,
    ): String {
        val doneIndices = main.indices.filter { main[it].done }
        if (doneIndices.isEmpty()) return "${main.size} sets · GOAL $goalDisplay"
        return if (partner == null) {
            doneIndices.joinToString(" · ") { i -> setSummary(main[i], tracking, unit) }
        } else {
            doneIndices.joinToString(" / ") { i ->
                val ss = partner.getOrNull(i)
                val mainText = setSummary(main[i], tracking, unit)
                if (ss == null) mainText else "$mainText(${setSummary(ss, partnerTracking, unit)})"
            }
        }
    }

    /**
     * The "last time: …" chip's value (PLAN.md A1 bonus, issue #14) — `null`
     * when [last] is `null` (the exercise has no prior completed performance),
     * in which case the card shows no chip at all. Formats by the logged
     * VALUE ([SetFormatter.summaryOfValues]), not the exercise's current
     * tracking type: a `session_set`/last-performed row can be legacy
     * reps-shaped for an exercise reclassified TIMED since (design risk #3 —
     * history is never touched by the P3 fixup, only live logs are).
     */
    fun lastTimeDisplay(last: LastPerformed?, unit: WeightUnit): String? =
        last?.let { SetFormatter.summaryOfValues(it.weightLb, it.reps, it.seconds, unit) }

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
        return display.takeIf { it != lastTimeDisplay(lastTime, unit) }
    }

    /**
     * The TOP set's one-line comparison against the last time this exercise was
     * performed (issue #127): "+5 LB FROM LAST", "MATCHED", "FIRST LOG". Derived
     * from what the card already holds — [main] is the live log the rows render
     * from and [last] is the read [lastTimeDisplay] already made — so narrating
     * the number costs no extra query.
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
    fun plateLine(main: List<LoggedSet>, equipment: List<Equipment>, unit: WeightUnit): cloud.trotter.log.strength.ui.text.UiText.DayPlate? {
        if (Equipment.BARBELL !in equipment) return null
        val next = main.firstOrNull { !it.done } ?: return null
        val perSide = PlateMath.perSide(unit.fromLb(next.weightLb), unit) ?: return null
        return cloud.trotter.log.strength.ui.text.UiText.DayPlate(perSide.takeIf { it.isNotEmpty() }?.joinToString(" + ") { WeightStepper.format(it) })
    }

    /**
     * The day header's status line once the session is underway (#126) —
     * "IN PROGRESS · 4 OF 18 SETS", or `null` before the first tick, when the
     * day has nothing to report and shows no line at all.
     *
     * The phase vocabulary is [TodayScreenBuilder.overline]'s: the day screen
     * speaks the same three phases Today, the widget and the watch already
     * speak rather than inventing a fourth, so "READY TO FINISH" means the same
     * thing everywhere it appears.
     */
    fun sessionStatusLine(doneSets: Int, totalSets: Int): cloud.trotter.log.strength.ui.text.UiText.DayStatus? =
        if (doneSets <= 0) {
            null
        } else {
            cloud.trotter.log.strength.ui.text.UiText.DayStatus(totalSets > 0 && doneSets >= totalSets, doneSets, totalSets)
        }

    /** True once every round is ticked — drives the green chip and auto-collapse. */
    fun allDone(main: List<LoggedSet>): Boolean = main.isNotEmpty() && main.all { it.done }

    /** Manual choice wins over auto (spec §8.2): auto-collapses only when all done. */
    fun collapsed(main: List<LoggedSet>, manualOverride: Boolean?): Boolean =
        manualOverride ?: allDone(main)

    private fun setSummary(set: LoggedSet, tracking: TrackingType, unit: WeightUnit): String =
        SetFormatter.summary(tracking, set.weightLb, set.reps, set.seconds, unit)

    private fun repWord(count: Int): String = if (count == 1) "REP" else "REPS"

    /** Half of [WeightStepper.format]'s last decimal place — the widest gap that
     *  can only be float noise once both sides are already at display
     *  precision. */
    private const val DISPLAY_EPSILON = 0.005
}
