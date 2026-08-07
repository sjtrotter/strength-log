package cloud.trotter.log.strength.sync

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
 * Applies a watch [SetEditDelta] to the phone's live logs — the phone-side end of
 * the wire protocol. Everything derived (RAMP/BACK-OFF cascade off a TOP edit, the
 * paired one-tick-per-round on a superset) is computed *here on the phone* through
 * the exact same `:domain` helpers the day screen uses ([SetEditor],
 * [DayScreenBuilder.applyRoundTick]) and written through the same repository paths
 * ([TrackerRepository.updateSets]/[updateSetsPaired][TrackerRepository.updateSetsPaired]);
 * the watch never computes a derived set. The resulting higher-revision snapshot is
 * the watch's ack (spec §9, last-write-wins).
 *
 * A done=true delta (main or partner row) also stamps the session-start time
 * if unset ([TrackerRepository.stampSessionStartIfUnset]) — watch-first
 * workouts exist, so the same first-tick-starts-the-clock rule the day screen
 * applies must hold here too (session-start capture).
 *
 * A delta may also carry the wrist-observed start/complete millis for its round
 * (#85). Those are persisted verbatim onto the row (and onto the aligned partner
 * row when the tick pairs) — the phone stores the facts and derives active time and
 * actual rest from them later; neither side computes a duration to store. A
 * `done = false` delta (the watch's long-press undo, #88) clears them again: an
 * untick retracts the tick's facts along with the tick.
 *
 * Three guards before any write:
 *  - **Validation** — the day, slot (programExerciseId + main/ss track) and set
 *    index must all exist; a superset "ss" delta needs a real partner. Anything
 *    else is [Outcome.INVALID] and touches no data (malformed input from an
 *    exported/foreign sender must never corrupt the log).
 *  - **Dedupe** — a delta whose `editedAtMillis` is not newer than the last one
 *    applied to that row is [Outcome.STALE] and dropped, which is what makes the
 *    watch's re-sends idempotent.
 *  - **Tracking guard** ([guardedFor], design risk #2) — the target exercise's
 *    [TrackingType] strips any field it doesn't track (a weight edit on a REPS/TIMED
 *    hold, a reps edit on a TIMED hold, a seconds edit on a weighted lift) so a
 *    stale/old watch that still draws the wrong control can never write a dead field.
 *    The MAIN track resolves against the slot's exercise, an SS track against its
 *    superset partner — each has its own type.
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
        delta.seconds?.let { if (it < 0) return Outcome.INVALID }
        delta.startedAtMillis?.let { if (it < 0) return Outcome.INVALID }
        delta.completedAtMillis?.let { if (it < 0) return Outcome.INVALID }

        // The slot must be a real exercise slot on a real day of the current program.
        val slots = repo.daySlotsFlow(delta.dayId).first()
        val slot = slots.firstOrNull { it.programExerciseId == delta.programExerciseId } ?: return Outcome.INVALID
        if (delta.slot == Slot.SS && slot.exercise.superset == null) return Outcome.INVALID

        val logs = repo.logFlow(delta.dayId).first()
        val track = logs.firstOrNull {
            it.programExerciseId == delta.programExerciseId && it.slot == delta.slot
        }?.sets.orEmpty()
        if (delta.setIndex !in track.indices) return Outcome.INVALID

        val rowKey = rowKey(delta)
        if (delta.editedAtMillis <= markers.lastApplied(rowKey)) return Outcome.STALE

        // Tracking guard: resolve the *edited* exercise's type (the partner's own
        // type for an SS delta) and drop any field that type doesn't track.
        val catalog = repo.catalogFlow.first()
        val editedExerciseId =
            if (delta.slot == Slot.SS) slot.exercise.superset?.exerciseId else slot.exercise.exerciseId
        val tracking = editedExerciseId?.let { catalog.find(it)?.tracking } ?: TrackingType.WEIGHTED
        val guarded = delta.guardedFor(tracking)

        if (delta.slot == Slot.MAIN) {
            applyToMain(guarded, track, partnerTrack = logs.trackOf(delta.programExerciseId, Slot.SS))
        } else {
            applyToPartner(guarded, track)
        }
        markers.markApplied(rowKey, delta.editedAtMillis)
        return Outcome.APPLIED
    }

    /**
     * Applies a watch [ExerciseSwapDelta] — "use one of the alternates you prescribed
     * for this slot" (#90). Runs under the same [mutationLock] as a set edit: a swap
     * clears the slot's log, and it must not interleave with a delta writing to it.
     *
     * The application itself is the phone's existing §8.3 swap and nothing else
     * ([TrackerRepository.swapExerciseById] — the slot keeps its `programExerciseId`,
     * its live log is cleared, the new exercise reseeds from its own GOAL on the next
     * observation). The watch contributes a choice, never a consequence.
     *
     * Four guards, in this order:
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
     */
    suspend fun apply(swap: ExerciseSwapDelta): Outcome = mutationLock.withLock {
        val slots = repo.daySlotsFlow(swap.dayId).first()
        val slot = slots.firstOrNull { it.programExerciseId == swap.programExerciseId }
            ?: return Outcome.INVALID

        if (slot.exercise.exerciseId == swap.exerciseId) return Outcome.APPLIED

        val rowKey = "${swap.dayId}|${swap.programExerciseId}|swap"
        if (swap.editedAtMillis <= markers.lastApplied(rowKey)) return Outcome.STALE

        val catalog = repo.catalogFlow.first()
        val equipment = repo.wizardAnswersFlow.first().equipment
        val prescribed = WatchSnapshotBuilder.alternatesFor(slot.exercise.exerciseId, catalog, equipment)
        if (prescribed.none { it.exerciseId == swap.exerciseId }) return Outcome.INVALID

        repo.swapExerciseById(swap.dayId, swap.programExerciseId, swap.exerciseId)
        seedSwappedSlot(swap.dayId, swap.programExerciseId, catalog)
        markers.markApplied(rowKey, swap.editedAtMillis)
        return Outcome.APPLIED
    }

    /**
     * Seeds the slot the swap just emptied, here and now.
     *
     * Everywhere else in the app seeding is the day ViewModel's job, done lazily on
     * the next observation — which works because a phone-driven swap happens with the
     * day screen open. A watch-driven one doesn't: it can land in a service that woke
     * a process with no screen in it, and the slot would sit with an empty track
     * until the lifter next opened the phone. The wrist would show a lift with no
     * sets in it, which is a worse answer than the swap they asked for.
     *
     * So it runs through the exact same [DayScreenBuilder.seedPlan] the day screen
     * uses, over the whole day's slots with the existing tracks passed in — the plan
     * only writes tracks that have none, so the emptied slot is the only thing it can
     * touch and every other lift's living record is left alone.
     *
     * The write goes through [TrackerRepository.seedIfEmpty], the same conditional
     * mutation the day screen's lazy pass uses, so the two seeders can't race: a plan
     * decided from a read the other one has already acted on becomes a no-op rather
     * than an overwrite of work logged in between.
     */
    private suspend fun seedSwappedSlot(dayId: String, programExerciseId: Long, catalog: ExerciseCatalog) {
        val slots = repo.daySlotsFlow(dayId).first()
        val existing = repo.logFlow(dayId).first()
            .associate { (it.programExerciseId to it.slot) to it.sets.size }
        val cfg = repo.configFlow.first()
        DayScreenBuilder.seedPlan(slots, existing, cfg, catalog)
            .filter { it.programExerciseId == programExerciseId }
            .forEach { write -> repo.seedIfEmpty(dayId, write.programExerciseId, write.slot, write.sets) }
    }

    private suspend fun applyToMain(
        delta: SetEditDelta,
        track: List<LoggedSet>,
        partnerTrack: List<LoggedSet>,
    ) {
        var main = track
        delta.weightLb?.let { main = SetEditor.editWeight(main, delta.setIndex, it) }
        delta.reps?.let { main = SetEditor.editReps(main, delta.setIndex, it) }
        delta.seconds?.let { main = SetEditor.editSeconds(main, delta.setIndex, it) }
        main = main.stamped(delta)

        val done = delta.done
        if (done != null) {
            if (done) repo.stampSessionStartIfUnset()
            // One-tick-per-round: a done on the main row flips the aligned partner
            // round too, atomically — same rule and repo path as the day screen. A
            // never-seeded partner stays missing (writing an empty SS row would mark
            // it seeded forever).
            val partner = partnerTrack.takeIf { it.isNotEmpty() }
            val (newMain, newPartner) = DayScreenBuilder.applyRoundTick(main, partner, delta.setIndex, done)
            if (newPartner != null) {
                // The round is one round: its start/complete facts belong to the
                // partner row too, written in the same paired transaction.
                repo.updateSetsPaired(delta.dayId, delta.programExerciseId, newMain, newPartner.stamped(delta))
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
        delta.seconds?.let { ss = SetEditor.editSeconds(ss, delta.setIndex, it) }
        ss = ss.stamped(delta)
        delta.done?.let { done ->
            if (done) repo.stampSessionStartIfUnset()
            ss = ss.mapIndexed { i, s -> if (i == delta.setIndex) s.copy(done = done) else s }
        }
        repo.updateSets(delta.dayId, delta.programExerciseId, Slot.SS, ss)
    }

    /** Writes the watch-observed start/complete millis onto the delta's row. They are
     *  facts, not tracked fields, so no [TrackingType] strips them ([guardedFor]) and
     *  they land whatever else the delta carries — in practice they ride with the
     *  tick that produced them. Null keeps the stored value, the same
     *  "null means unchanged" rule every other delta field follows.
     *
     *  An **untick is the exception**: `done = false` retracts the tick, and the
     *  stamps are facts *about that tick*. They clear with it, whether or not the
     *  delta carries any (the watch's undo carries none) — the same rule
     *  `CheckmarkReset` applies when a stale check clears at the day boundary.
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

    private fun List<cloud.trotter.log.strength.data.LoggedSlot>.trackOf(
        programExerciseId: Long,
        slot: String,
    ): List<LoggedSet> =
        firstOrNull { it.programExerciseId == programExerciseId && it.slot == slot }?.sets.orEmpty()

    /** Per-ROW, not per-track: a per-track marker would let a newer edit to row 1
     *  permanently STALE-starve a delayed re-send of a distinct row-0 edit. A
     *  replayed edit still dedupes — it carries the same row and the same stamp. */
    private fun rowKey(delta: SetEditDelta): String =
        "${delta.dayId}|${delta.programExerciseId}|${delta.slot}|${delta.setIndex}"
}
