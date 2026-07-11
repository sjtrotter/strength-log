package io.github.sjtrotter.strengthlog.ui.day

import io.github.sjtrotter.strengthlog.data.LastPerformed
import io.github.sjtrotter.strengthlog.data.PersonalRecord
import io.github.sjtrotter.strengthlog.data.ProgramSlot
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.library.ExerciseEntry
import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.seeding.SetSeeder
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.standards.SetFormatter
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

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

    /** Helper line under a main lift's header (spec §8.2, design-pass copy). */
    const val MAIN_HELPER = "Change the TOP set — ramp & back-off recalculate."

    /** Helper line under a superset's header (design-pass copy: one tick per round). */
    const val SUPERSET_HELPER = "One tick checks the whole round — both moves, back-to-back."

    /** One log write emitted by [seedPlan]. */
    data class SeedWrite(
        val programExerciseId: Long,
        val slot: String,
        val sets: List<LoggedSet>,
    )

    /**
     * Which slots still need their ACTUAL log seeded from GOAL — every slot whose
     * (id, track) pair isn't already in [existing]. "Seeded once, then persists":
     * a slot with a stored log is never reseeded, so a changed GOAL never rewrites
     * a lifter's living record (spec principle 2).
     */
    fun seedPlan(
        slots: List<ProgramSlot>,
        existing: Set<Pair<Long, String>>,
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
                writes += SeedWrite(
                    slot.programExerciseId,
                    Slot.SS,
                    SetSeeder.seedPartner(mainSeed.size, GoalCalculator.targetFor(partnerEntry, cfg)),
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
     * io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity]'s stored
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

    /** True once every round is ticked — drives the green chip and auto-collapse. */
    fun allDone(main: List<LoggedSet>): Boolean = main.isNotEmpty() && main.all { it.done }

    /** Manual choice wins over auto (spec §8.2): auto-collapses only when all done. */
    fun collapsed(main: List<LoggedSet>, manualOverride: Boolean?): Boolean =
        manualOverride ?: allDone(main)

    private fun setSummary(set: LoggedSet, tracking: TrackingType, unit: WeightUnit): String =
        SetFormatter.summary(tracking, set.weightLb, set.reps, set.seconds, unit)
}
