package io.github.sjtrotter.strengthlog.sync

import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.seeding.SetEditor
import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.ui.day.DayScreenBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies a watch [SetEditDelta] to the phone's live logs — the phone-side end of
 * the wire protocol. Everything derived (RAMP/BACK-OFF cascade off a TOP edit, the
 * paired one-tick-per-round on a superset) is computed *here on the phone* through
 * the exact same `:domain` helpers the day screen uses ([SetEditor],
 * [DayScreenBuilder.applyRoundTick]) and written through the same repository paths
 * ([TrackerRepository.updateSets]/[updateSetsPaired][TrackerRepository.updateSetsPaired]);
 * the watch never computes a derived set. The resulting higher-revision snapshot is
 * the watch's ack (spec §9, last-write-wins).
 *
 * Two guards before any write:
 *  - **Validation** — the day, slot (programExerciseId + main/ss track) and set
 *    index must all exist; a superset "ss" delta needs a real partner. Anything
 *    else is [Outcome.INVALID] and touches no data (malformed input from an
 *    exported/foreign sender must never corrupt the log).
 *  - **Dedupe** — a delta whose `editedAtMillis` is not newer than the last one
 *    applied to that slot is [Outcome.STALE] and dropped, which is what makes the
 *    watch's re-sends idempotent.
 *
 * Read-modify-write over a whole track, so it holds [mutationLock] for the same
 * lost-update reason the day ViewModel serializes its own edits. That lock is
 * per-applier (a process singleton): it serializes concurrent *watch* deltas.
 * A watch edit racing a phone-UI edit on the identical track is not co-serialized
 * with the ViewModel's separate lock, which is acceptable and spec-blessed — one
 * user, one pair of hands, last-write-wins (§9).
 */
class SetEditApplier(
    private val repo: TrackerRepository,
    private val markers: AppliedEditMarkers,
) {

    enum class Outcome { APPLIED, STALE, INVALID }

    private val mutationLock = Mutex()

    suspend fun apply(delta: SetEditDelta): Outcome = mutationLock.withLock {
        if (delta.slot != Slot.MAIN && delta.slot != Slot.SS) return Outcome.INVALID
        // Value hardening: this arrives through an exported service, so assume
        // hostile input. Negative or non-finite numbers must never reach the log
        // (zero reps stays legal — 0-rep rows exist).
        delta.weightLb?.let { if (!it.isFinite() || it < 0.0) return Outcome.INVALID }
        delta.reps?.let { if (it < 0) return Outcome.INVALID }

        // The slot must be a real exercise slot on a real day of the current program.
        val slots = repo.daySlotsFlow(delta.dayId).first()
        val slot = slots.firstOrNull { it.programExerciseId == delta.programExerciseId } ?: return Outcome.INVALID
        if (delta.slot == Slot.SS && slot.exercise.superset == null) return Outcome.INVALID

        val logs = repo.logFlow(delta.dayId).first()
        val track = logs.firstOrNull {
            it.programExerciseId == delta.programExerciseId && it.slot == delta.slot
        }?.sets.orEmpty()
        if (delta.setIndex !in track.indices) return Outcome.INVALID

        val slotKey = slotKey(delta)
        if (delta.editedAtMillis <= markers.lastApplied(slotKey)) return Outcome.STALE

        if (delta.slot == Slot.MAIN) {
            applyToMain(delta, track, partnerTrack = logs.trackOf(delta.programExerciseId, Slot.SS))
        } else {
            applyToPartner(delta, track)
        }
        markers.markApplied(slotKey, delta.editedAtMillis)
        return Outcome.APPLIED
    }

    private suspend fun applyToMain(
        delta: SetEditDelta,
        track: List<LoggedSet>,
        partnerTrack: List<LoggedSet>,
    ) {
        var main = track
        delta.weightLb?.let { main = SetEditor.editWeight(main, delta.setIndex, it) }
        delta.reps?.let { main = SetEditor.editReps(main, delta.setIndex, it) }

        val done = delta.done
        if (done != null) {
            // One-tick-per-round: a done on the main row flips the aligned partner
            // round too, atomically — same rule and repo path as the day screen. A
            // never-seeded partner stays missing (writing an empty SS row would mark
            // it seeded forever).
            val partner = partnerTrack.takeIf { it.isNotEmpty() }
            val (newMain, newPartner) = DayScreenBuilder.applyRoundTick(main, partner, delta.setIndex, done)
            if (newPartner != null) {
                repo.updateSetsPaired(delta.dayId, delta.programExerciseId, newMain, newPartner)
                return
            }
            main = newMain
        }
        repo.updateSets(delta.dayId, delta.programExerciseId, Slot.MAIN, main)
    }

    private suspend fun applyToPartner(delta: SetEditDelta, track: List<LoggedSet>) {
        var ss = track
        delta.weightLb?.let { ss = SetEditor.editWeight(ss, delta.setIndex, it) }
        delta.reps?.let { ss = SetEditor.editReps(ss, delta.setIndex, it) }
        delta.done?.let { done ->
            ss = ss.mapIndexed { i, s -> if (i == delta.setIndex) s.copy(done = done) else s }
        }
        repo.updateSets(delta.dayId, delta.programExerciseId, Slot.SS, ss)
    }

    private fun List<io.github.sjtrotter.strengthlog.data.LoggedSlot>.trackOf(
        programExerciseId: Long,
        slot: String,
    ): List<LoggedSet> =
        firstOrNull { it.programExerciseId == programExerciseId && it.slot == slot }?.sets.orEmpty()

    private fun slotKey(delta: SetEditDelta): String =
        "${delta.dayId}|${delta.programExerciseId}|${delta.slot}"
}
