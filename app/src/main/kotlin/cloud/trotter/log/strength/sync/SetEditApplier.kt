package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.isSlotSkipped
import cloud.trotter.log.strength.data.performedExercise
import cloud.trotter.log.strength.data.ExerciseSwapResult
import cloud.trotter.log.strength.data.TrackEdit
import cloud.trotter.log.strength.data.RunWriteResult
import cloud.trotter.log.strength.data.forRun
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.seeding.SetEdit
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.guardedFor
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
    private val plans: PlanRepository,
    private val catalog: CatalogRepository,
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
        val planId = delta.planId ?: plans.planIdOfSlot(delta.programExerciseId) ?: return Outcome.INVALID
        val dayTracks = repo.dayTracksFlow(planId, delta.dayId).first()
        if (repo.openRunFlow.first()?.isSlotSkipped(delta.programExerciseId, delta.slot) == true) return Outcome.REJECTED
        val slots = plans.slotsFor(planId, delta.dayId).first().forRun(dayTracks.droppedPartners, dayTracks.skippedLifts)
        val slot = slots.firstOrNull { it.programExerciseId == delta.programExerciseId } ?: return Outcome.INVALID
        if (delta.slot == Slot.SS && slot.exercise.superset == null) return Outcome.INVALID

        val catalog = this.catalog.catalogFlow.first()
        val tracks = dayTracks.tracks
        val cfg = plans.wizardAnswersFor(planId).first().config
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
                SetEditor.edit(track, delta.setIndex, SetEdit(
                    weightLb = guarded.weightLb.takeIf { values },
                    reps = guarded.reps.takeIf { values },
                    seconds = guarded.seconds.takeIf { values },
                    done = delta.done,
                    startedAtMillis = delta.startedAtMillis,
                    completedAtMillis = delta.completedAtMillis,
                    watchEditedAt = delta.editedAtMillis.takeIf { values },
                ), cfg.weightUnit, performed.entry?.equipment.orEmpty())
            }
        }
        edits.add(edit(delta.slot, true))
        if (delta.done != null) {
            val otherSlot = if (delta.slot == Slot.MAIN) Slot.SS else Slot.MAIN
            val otherSeed = if (delta.runId == null) null else {
                val performed = repo.observeDay(planId, delta.dayId).first().slots
                    .firstOrNull { it.slot.programExerciseId == delta.programExerciseId }
                if (otherSlot == Slot.SS) performed?.partner?.seed else performed?.main?.seed
            }
            if (tracks.any { it.programExerciseId == delta.programExerciseId && it.slot == otherSlot && it.sets.isNotEmpty() } ||
                otherSeed?.isNotEmpty() == true) {
                edits.add(edit(otherSlot, false))
            }
        }
        val runId = delta.runId
        val result = if (runId == null) {
            repo.applyToOpenRun(planId, delta.dayId, delta.editedAtMillis, edits)
        } else {
            repo.applyToRun(runId, planId, delta.dayId, edits)
        }
        return when (result) {
            is RunWriteResult.InRun -> Outcome.APPLIED
            RunWriteResult.Rejected, RunWriteResult.NeedsStart -> Outcome.REJECTED
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
        val planId = swap.planId ?: plans.planIdOfSlot(swap.programExerciseId) ?: return Outcome.INVALID
        val slots = plans.slotsFor(planId, swap.dayId).first()
        val slot = slots.firstOrNull { it.programExerciseId == swap.programExerciseId }
            ?: return Outcome.INVALID

        val catalog = this.catalog.catalogFlow.first()
        val tracks = repo.dayTracksFlow(planId, swap.dayId).first()
        val performed = performedExercise(slot, tracks.tracks.firstOrNull {
            it.programExerciseId == slot.programExerciseId && it.slot == Slot.MAIN
        }?.exerciseId, catalog)
        val rowKey = "${swap.dayId}|${swap.programExerciseId}|swap"
        val stale = swap.editedAtMillis <= markers.lastApplied(rowKey)
        val answers = plans.wizardAnswersFor(planId).first()
        val declined = when {
            stale -> Outcome.STALE
            swap.exerciseId != slot.exercise.exerciseId && swap.exerciseId != performed.exerciseId &&
                WatchSnapshotBuilder.alternatesFor(performed.exerciseId, catalog, answers.equipment)
                    .none { it.exerciseId == swap.exerciseId } -> Outcome.INVALID
            else -> null
        }
        val runId = swap.runId
        when (repo.applyExerciseSwap(
            planId, swap.dayId, slot.programExerciseId, swap.exerciseId, runId,
            allowChange = declined == null,
        )) {
            ExerciseSwapResult.ALREADY_THERE -> return Outcome.APPLIED
            ExerciseSwapResult.REJECTED -> return declined ?: Outcome.REJECTED
            ExerciseSwapResult.CHANGED -> if (runId == null) {
                repo.ensureSeeded(planId, swap.dayId)
            }
        }
        markers.markApplied(rowKey, swap.editedAtMillis)
        return Outcome.APPLIED
    }
}
