package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.RunSeed
import cloud.trotter.log.strength.data.TrackEdit
import cloud.trotter.log.strength.data.RunWriteResult
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
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
        val slots = repo.slotsFor(planId, delta.dayId).first()
        val slot = slots.firstOrNull { it.programExerciseId == delta.programExerciseId } ?: return Outcome.INVALID
        if (delta.slot == Slot.SS && slot.exercise.superset == null) return Outcome.INVALID

        val catalog = repo.catalogFlow.first()
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
                val exerciseId = if (trackSlot == Slot.SS) slot.exercise.superset?.exerciseId else slot.exercise.exerciseId
                val prescription = slot.exercise.prescription.takeIf { trackSlot == Slot.MAIN }
                val tracking = prescription?.tracking ?: exerciseId?.let { catalog.find(it)?.tracking } ?: TrackingType.WEIGHTED
                val guarded = delta.guardedFor(tracking)
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
        val tracks = repo.dayTracksFlow(planId, delta.dayId).first().tracks
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
     * Applies a watch [ExerciseSwapDelta] — "use one of the alternates you prescribed
     * for this slot" (docs/briefs/wear-dial-v3.md §5). Runs under the same
     * [mutationLock] as a set edit: a swap clears the slot's log, and it must not
     * interleave with a delta writing to it.
     *
     * The application itself is the phone's existing §8.3 swap and nothing else
     * ([TrackerRepository.swapExerciseById] — the slot keeps its `programExerciseId`,
     * its working values are cleared). A swap during a run retires the old track
     * when it holds done work and seeds the new exercise into the run.
     *
     * Validation and reseeding follow the slot's plan, independent of selection.
     *
     * Guards:
     *  - **Validation** — the day must hold a slot with this `programExerciseId`.
     *  - **Already there** — a slot already holding the requested exercise is
     *    [Outcome.APPLIED] with no write, and it is checked before anything that
     *    could fall through to one. This is what makes a re-send safe: the watch
     *    re-sends until a snapshot confirms, and a blind second swap would clear a
     *    log the lifter has since filled.
     *  - **Dedupe** — per slot, by `editedAtMillis`, same rule as a set edit. Ahead
     *    of the authority check because a message we have already answered is not
     *    worth evaluating, and because a hostile far-future stamp must be rejected
     *    *without* marking anything: the marker only advances on a real write, so a
     *    forged stamp can never starve a legitimate swap.
     *  - **Authority** — the requested exercise must be one of the alternates *this
     *    phone* would prescribe for the slot right now, re-derived through the same
     *    [WatchSnapshotBuilder.alternatesFor] that put them on the wire rather than
     *    trusted from the message. "The watch never invents alternates" (brief §6) is
     *    an enforced invariant, not a convention, because this arrives through an
     *    exported service. It also rejects, correctly, a swap chosen against a
     *    snapshot the phone has since moved past.
     *  - **Run identity** — validated inside the swap transaction so Finish cannot
     *    retarget a delayed swap to another run.
     */
    suspend fun apply(swap: ExerciseSwapDelta): Outcome = mutationLock.withLock {
        applySwap(swap).also { ackLog.recordProcessedEdit(swap.editedAtMillis) }
    }

    private suspend fun applySwap(swap: ExerciseSwapDelta): Outcome {
        val planId = swap.planId ?: repo.planIdOfSlot(swap.programExerciseId) ?: return Outcome.INVALID
        val slots = repo.slotsFor(planId, swap.dayId).first()
        val slot = slots.firstOrNull { it.programExerciseId == swap.programExerciseId }
            ?: return Outcome.INVALID

        if (slot.exercise.exerciseId == swap.exerciseId) return Outcome.APPLIED

        val rowKey = "${swap.dayId}|${swap.programExerciseId}|swap"
        if (swap.editedAtMillis <= markers.lastApplied(rowKey)) return Outcome.STALE

        val catalog = repo.catalogFlow.first()
        val equipment = repo.wizardAnswersFor(planId).first().equipment
        val prescribed = WatchSnapshotBuilder.alternatesFor(slot.exercise.exerciseId, catalog, equipment)
        if (prescribed.none { it.exerciseId == swap.exerciseId }) return Outcome.INVALID

        if (!repo.swapExerciseById(swap.dayId, swap.programExerciseId, swap.exerciseId, swap.runId)) {
            return Outcome.REJECTED
        }
        seedSwappedSlot(planId, swap.dayId, swap.programExerciseId, catalog)
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
     *  facts, not tracked fields, so no [TrackingType] strips them ([guardedFor]) and
     *  they land whatever else the delta carries — in practice they ride with the
     *  tick that produced them. Null keeps the stored value, the same
     *  "null means unchanged" rule every other delta field follows.
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
                else -> s.copy(
                    startedAtMillis = delta.startedAtMillis ?: s.startedAtMillis,
                    completedAtMillis = delta.completedAtMillis ?: s.completedAtMillis,
                )
            }
        }
    }

}
