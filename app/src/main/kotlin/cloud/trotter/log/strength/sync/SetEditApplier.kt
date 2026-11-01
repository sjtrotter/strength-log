package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.isSlotSkipped
import cloud.trotter.log.strength.data.performedExercise
import cloud.trotter.log.strength.data.ExerciseSwapResult
import cloud.trotter.log.strength.data.RunSeed
import cloud.trotter.log.strength.data.TrackEdit
import cloud.trotter.log.strength.data.RunWriteResult
import cloud.trotter.log.strength.data.forRun
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.guardedFor
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deltas with a run go through the gate: a closed or unknown run is rejected,
 * and a done tick on the day's next run starts it. This closes #296 for updated watches.
 * Deltas without a run are an old watch: bounded by closedAt, never starting a run.
 * Row stamps and paired ticks commit with the edits in the same Room transaction.
 */
class SetEditApplier(
    private val repo: TrackerRepository,
    private val markers: AppliedEditMarkers,
    private val ackLog: EditAckLog,
) {

    enum class Outcome { APPLIED, STALE, INVALID, REJECTED }

    private val mutationLock = Mutex()

    /** Throws leave the stamp unacknowledged; repository transactions must return first. */
    suspend fun apply(delta: SetEditDelta): Outcome = mutationLock.withLock {
        applyEdit(delta).also { ackLog.recordProcessedEdit(delta.editedAtMillis) }
    }

    private suspend fun applyEdit(delta: SetEditDelta): Outcome {
        if (delta.slot != Slot.MAIN && delta.slot != Slot.SS) return Outcome.INVALID
        // Value hardening: this arrives through an exported service, so assume
        // hostile input. Negative or non-finite numbers must never reach the log
        // (zero reps stays legal — 0-rep rows exist).
        delta.weightLb?.let { if (!it.isFinite() || it < 0.0) return Outcome.INVALID }
        delta.reps?.let { if (it < 0) return Outcome.INVALID }
        delta.seconds?.let { if (it < 0) return Outcome.INVALID }
        delta.startedAtMillis?.let { if (it < 0) return Outcome.INVALID }
        delta.completedAtMillis?.let { if (it < 0) return Outcome.INVALID }

        // A delayed edit still belongs to the slot's plan after a phone plan switch.
        val planId = delta.planId ?: repo.planIdOfSlot(delta.programExerciseId) ?: return Outcome.INVALID
        val dayTracks = repo.dayTracksFlow(planId, delta.dayId).first()
        if (repo.openRunFlow.first()?.isSlotSkipped(delta.programExerciseId, delta.slot) == true) return Outcome.REJECTED
        val slots = repo.slotsFor(planId, delta.dayId).first().forRun(dayTracks.droppedPartners, dayTracks.skippedLifts)
        val slot = slots.firstOrNull { it.programExerciseId == delta.programExerciseId } ?: return Outcome.INVALID
        if (delta.slot == Slot.SS && slot.exercise.superset == null) return Outcome.INVALID

        val catalog = repo.catalogFlow.first()
        val tracks = dayTracks.tracks
        var declined: Outcome? = null
        val edits = mutableListOf<TrackEdit>()
        fun edit(trackSlot: String, values: Boolean): TrackEdit = TrackEdit(
            delta.programExerciseId, trackSlot,
            exerciseId = delta.exerciseId?.takeIf { delta.runId != null && values && it.isNotBlank() },
        ) { track ->
            val row = track.getOrNull(delta.setIndex)
            if (row == null) {
                if (values) {
                    declined = Outcome.INVALID
                    null
                } else track
            } else if (values && (row.watchEditedAt ?: 0) >= delta.editedAtMillis) {
                declined = Outcome.STALE
                null
            } else {
                val exerciseId = delta.exerciseId?.takeIf { delta.runId != null && values && it.isNotBlank() }
                    ?: tracks.firstOrNull { it.programExerciseId == slot.programExerciseId && it.slot == trackSlot }?.exerciseId
                val performed = performedExercise(slot, exerciseId, catalog, trackSlot)
                val guarded = delta.guardedFor(performed.tracking)
                var updated = track
                if (values) {
                    guarded.weightLb?.let { updated = SetEditor.editWeight(updated, delta.setIndex, it) }
                    guarded.reps?.let { updated = SetEditor.editReps(updated, delta.setIndex, it) }
                    guarded.seconds?.let { updated = SetEditor.editSeconds(updated, delta.setIndex, it) }
                }
                updated.stamped(delta).mapIndexed { i, set ->
                    if (i == delta.setIndex) set.copy(done = delta.done ?: set.done, watchEditedAt = if (values) delta.editedAtMillis else set.watchEditedAt)
                    else set
                }
            }
        }
        val seeds = if (delta.runId == null) emptyList() else {
            val existing = tracks
                .associate { (it.programExerciseId to it.slot) to it.sets.size }
            val cfg = repo.wizardAnswersFor(planId).first().config
            DayScreenBuilder.seedPlan(slots, existing, cfg, catalog)
                .map { RunSeed(it.programExerciseId, it.slot, it.sets) }
        }
        edits.add(edit(delta.slot, true))
        if (delta.done != null) {
            val otherSlot = if (delta.slot == Slot.MAIN) Slot.SS else Slot.MAIN
            if (tracks.any { it.programExerciseId == delta.programExerciseId && it.slot == otherSlot && it.sets.isNotEmpty() } ||
                seeds.any { it.programExerciseId == delta.programExerciseId && it.slot == otherSlot && it.sets.isNotEmpty() }) {
                edits.add(edit(otherSlot, false))
            }
        }
        val runId = delta.runId
        val result = if (runId == null) {
            repo.applyToOpenRun(planId, delta.dayId, delta.editedAtMillis, edits)
        } else {
            repo.applyToRun(runId, planId, delta.dayId, edits, seeds)
        }
        return when (result) {
            is RunWriteResult.InRun -> Outcome.APPLIED
            RunWriteResult.Rejected -> Outcome.REJECTED
            RunWriteResult.Unchanged -> declined ?: Outcome.INVALID
            RunWriteResult.WorkingValues -> Outcome.APPLIED
        }
    }

    /**
     * Applies a watch [ExerciseSwapDelta] under the set edits' [mutationLock].
     * A run-identified swap changes only the workout, starting the day's next run
     * if needed. Legacy swaps change the plan; returning to its exercise reopens
     * the original run track. Validation and seeding follow the slot's owning plan.
     *
     * Guards:
     * - **Validation** — the named slot must belong to this plan and day.
     * - **Already there** — the repository compares the performed exercise inside
     *   the swap transaction. A duplicate returns [Outcome.APPLIED] without a write,
     *   even with a stale stamp; it cannot reopen a retired track or start a run.
     * - **Dedupe before authority** — only a newer stamp can authorize a change.
     *   Markers advance only after a committed mutation, so a forged far-future
     *   stamp on an unauthorized swap cannot starve a legitimate swap.
     * - **Authority** — targets are the plan exercise and the performed exercise's
     *   alternates, re-derived through [WatchSnapshotBuilder.alternatesFor] with
     *   this plan's equipment. The exported service never trusts message names
     *   or a watch-provided alternates list.
     * - **Run identity** — checked in the mutation transaction so Finish cannot
     *   retarget a delayed swap to a successor run. A refusal is acknowledged;
     *   a thrown transaction leaves its stamp unacknowledged for retry.
     */
    suspend fun apply(swap: ExerciseSwapDelta): Outcome = mutationLock.withLock {
        applySwap(swap).also { ackLog.recordProcessedEdit(swap.editedAtMillis) }
    }

    private suspend fun applySwap(swap: ExerciseSwapDelta): Outcome {
        val planId = swap.planId ?: repo.planIdOfSlot(swap.programExerciseId) ?: return Outcome.INVALID
        val slots = repo.slotsFor(planId, swap.dayId).first()
        val slot = slots.firstOrNull { it.programExerciseId == swap.programExerciseId }
            ?: return Outcome.INVALID

        val catalog = repo.catalogFlow.first()
        val tracks = repo.dayTracksFlow(planId, swap.dayId).first()
        val performed = performedExercise(slot, tracks.tracks.firstOrNull {
            it.programExerciseId == slot.programExerciseId && it.slot == Slot.MAIN
        }?.exerciseId, catalog)
        val rowKey = "${swap.dayId}|${swap.programExerciseId}|swap"
        val stale = swap.editedAtMillis <= markers.lastApplied(rowKey)
        val answers = repo.wizardAnswersFor(planId).first()
        val declined = when {
            stale -> Outcome.STALE
            swap.exerciseId != slot.exercise.exerciseId && swap.exerciseId != performed.exerciseId &&
                WatchSnapshotBuilder.alternatesFor(performed.exerciseId, catalog, answers.equipment)
                    .none { it.exerciseId == swap.exerciseId } -> Outcome.INVALID
            else -> null
        }
        val existing = tracks.tracks.associate { (it.programExerciseId to it.slot) to it.sets.size }
        val runId = swap.runId
        val seeds = if (runId != null && declined == null) {
            DayScreenBuilder.seedPlan(slots, existing, answers.config, catalog)
                .map { RunSeed(it.programExerciseId, it.slot, it.sets) }
        } else emptyList()
        val seed = if (runId != null && declined == null) {
            DayScreenBuilder.swapSeed(slot, Slot.MAIN, swap.exerciseId, existing, answers.config, catalog)
                ?.let { RunSeed(it.programExerciseId, it.slot, it.sets) }
        } else null
        when (repo.applyExerciseSwap(
            planId, swap.dayId, slot.programExerciseId, swap.exerciseId, runId, seed, seeds,
            allowChange = declined == null,
        )) {
            ExerciseSwapResult.ALREADY_THERE -> return Outcome.APPLIED
            ExerciseSwapResult.REJECTED -> return declined ?: Outcome.REJECTED
            ExerciseSwapResult.CHANGED -> if (runId == null) {
                seedSwappedSlot(planId, swap.dayId, swap.programExerciseId, catalog)
            }
        }
        markers.markApplied(rowKey, swap.editedAtMillis)
        return Outcome.APPLIED
    }

    /** Seeds the swapped slot from its current prescription or goal, aligned to existing tracks.
     * A swap during a run retires the old track with done work and seeds the new one into the run. */
    private suspend fun seedSwappedSlot(planId: Long, dayId: String, programExerciseId: Long, catalog: ExerciseCatalog) {
        val slots = repo.slotsFor(planId, dayId).first()
        val tracks = repo.dayTracksFlow(planId, dayId).first()
        val existing = tracks.tracks.associate { (it.programExerciseId to it.slot) to it.sets.size }
        val cfg = repo.wizardAnswersFor(planId).first().config
        val seeds = DayScreenBuilder.seedPlan(slots, existing, cfg, catalog)
            .filter { it.programExerciseId == programExerciseId }
        val runId = tracks.runId
        if (tracks.runOpen && runId != null) {
            repo.seedRunTracksIfMissing(runId, seeds.map { RunSeed(it.programExerciseId, it.slot, it.sets) })
        } else {
            seeds.forEach { repo.seedIfEmpty(dayId, it.programExerciseId, it.slot, it.sets) }
        }
    }

    /** Writes the watch-observed start/complete millis onto the delta's row. They are
     *  facts, not tracked fields, so no tracking guard strips them ([guardedFor]) and
     *  they land whatever else the delta carries — in practice they ride with the
     *  tick that produced them. Null keeps the stored value, the same
     *  "null means unchanged" rule every other delta field follows.
     *
     *  A done tick preserves stored timing on either track, filling only missing stamps.
     *
     *  An **untick is the exception**: `done = false` retracts the tick, and the
     *  stamps are facts *about that tick*. They clear with it, whether or not the
     *  delta carries any (the watch's undo carries none).
     *  Leaving a start/complete pair on a row that now reads unchecked would let a
     *  duration nobody performed ride into the day's derived active time. */
    private fun List<LoggedSet>.stamped(delta: SetEditDelta): List<LoggedSet> {
        val unticking = delta.done == false
        if (!unticking && delta.startedAtMillis == null && delta.completedAtMillis == null) return this
        return mapIndexed { i, s ->
            when {
                i != delta.setIndex -> s
                unticking -> s.copy(startedAtMillis = null, completedAtMillis = null)
                delta.done == true && s.done -> s.copy(
                    startedAtMillis = s.startedAtMillis ?: delta.startedAtMillis,
                    completedAtMillis = s.completedAtMillis ?: delta.completedAtMillis,
                )
                else -> s.copy(
                    startedAtMillis = delta.startedAtMillis ?: s.startedAtMillis,
                    completedAtMillis = delta.completedAtMillis ?: s.completedAtMillis,
                )
            }
        }
    }

}
