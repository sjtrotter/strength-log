package io.github.sjtrotter.strengthlog.ui.day

import io.github.sjtrotter.strengthlog.data.ProgramSlot
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.seeding.SetSeeder
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
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
            val goal = GoalCalculator.goalFor(entry, cfg)
            val mainSeed = SetSeeder.seed(pe, goal, cfg)
            if (slot.programExerciseId to Slot.MAIN !in existing) {
                writes += SeedWrite(slot.programExerciseId, Slot.MAIN, mainSeed)
            }
            val partner = pe.superset
            if (partner != null && slot.programExerciseId to Slot.SS !in existing) {
                val partnerEntry = catalog.find(partner.exerciseId) ?: continue
                val partnerGoal = GoalCalculator.goalFor(partnerEntry, cfg)
                writes += SeedWrite(
                    slot.programExerciseId,
                    Slot.SS,
                    SetSeeder.seedPartner(mainSeed.size, partnerGoal),
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
    fun kindLabels(sets: List<LoggedSet>): List<String> {
        var ramp = 0
        return sets.mapIndexed { index, s ->
            when (s.kind) {
                SetKind.RAMP -> "R${++ramp}"
                SetKind.TOP -> "TOP"
                SetKind.BACKOFF -> "B/O"
                SetKind.WORK, SetKind.EXTRA -> "${index + 1}"
            }
        }
    }

    /**
     * The collapsed one-line summary (spec §8.2): completed rows as `w×r` joined by
     * " · ", or `w×r(ssW×ssR)` joined by " / " for a superset. When nothing is
     * checked yet, `{n} sets · GOAL {g}`.
     */
    fun collapsedSummary(
        main: List<LoggedSet>,
        partner: List<LoggedSet>?,
        goalDisplay: String,
        unit: WeightUnit,
    ): String {
        val doneIndices = main.indices.filter { main[it].done }
        if (doneIndices.isEmpty()) return "${main.size} sets · GOAL $goalDisplay"
        return if (partner == null) {
            doneIndices.joinToString(" · ") { i -> wxr(main[i], unit) }
        } else {
            doneIndices.joinToString(" / ") { i ->
                val ss = partner.getOrNull(i)
                if (ss == null) wxr(main[i], unit) else "${wxr(main[i], unit)}(${wxr(ss, unit)})"
            }
        }
    }

    /** GOAL number formatted in [unit] (canonical lb in, display out). */
    fun goalDisplay(goalLb: Double, unit: WeightUnit): String =
        WeightStepper.format(unit.fromLb(goalLb))

    /** True once every round is ticked — drives the green chip and auto-collapse. */
    fun allDone(main: List<LoggedSet>): Boolean = main.isNotEmpty() && main.all { it.done }

    /** Manual choice wins over auto (spec §8.2): auto-collapses only when all done. */
    fun collapsed(main: List<LoggedSet>, manualOverride: Boolean?): Boolean =
        manualOverride ?: allDone(main)

    private fun wxr(set: LoggedSet, unit: WeightUnit): String =
        "${WeightStepper.format(unit.fromLb(set.weightLb))}×${set.reps}"
}
