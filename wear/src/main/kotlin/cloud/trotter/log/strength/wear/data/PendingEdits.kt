package cloud.trotter.log.strength.wear.data

import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlin.math.abs

/**
 * The watch's outbound-queue drain rule, kept pure so §11.4's "an edit survives a
 * phone-app restart" is JVM-testable without a device.
 *
 * MessageClient is fire-and-forget, so [cloud.trotter.log.strength.wear.data.DataLayerWatchClient]
 * queues every edit and re-sends it until the phone's next snapshot *reflects* it —
 * the round-trip snapshot is the only ack (spec §9, last-write-wins). Because the
 * phone dedupes replays by `editedAtMillis`, re-sending an already-applied delta is
 * harmless, so the queue can safely resend everything still pending on any signal.
 *
 * Settling is decided **per field**, not per delta: the dial sends one tick delta
 * carrying `done` plus the round's start/complete stamps, so "did this land?" has to
 * be answered field by field. A carried field is *accounted for* when either:
 *  - the snapshot's addressed row shows that field's requested value; or
 *  - a strictly newer pending delta to the same row carries the same field — the
 *    snapshot will only ever show the newer value, so this one could never be
 *    confirmed and would re-send forever (the phone drops it as stale anyway).
 *
 * A delta drops out of the queue when every field it carries is accounted for, or
 * when the snapshot is for a *different* suggested day — the phone has moved on (the
 * day was completed/advanced), so an old-day edit can neither be confirmed nor is
 * worth resending; last-write-wins lets it go.
 *
 * [SetEditDelta.startedAtMillis]/[SetEditDelta.completedAtMillis] impose no condition
 * of their own: the snapshot has no place to echo them (the watch never displays
 * them, so echoing would be wire weight nobody reads), and the phone writes them in
 * the same transaction as the rest of the delta. They therefore ride with the fields
 * that *are* visible — settle when those settle. The one shape this can't ack is a
 * delta carrying stamps and nothing else; such a delta settles vacuously on the first
 * snapshot for its day. The dial never emits that shape (stamps always ride a tick),
 * and the alternative — never settling — would re-send forever for a fact the wire
 * can't confirm.
 */
object PendingEdits {

    /**
     * Cardio settles on the snapshot's ack stamp and nothing else: a completion
     * is history, so neither a day rollover nor a program switch may retire an
     * undelivered one (the 23:55 run must survive to the 00:05 reconnect). An
     * old phone publishing the default 0 simply never settles the queue, and
     * its content dedupe absorbs the re-sends.
     */
    fun reconcileCardio(pending: List<CardioDelta>, snapshot: WatchSnapshot): List<CardioDelta> =
        pending.filter { delta -> delta.stamp > snapshot.cardioAckStamp }

    /** The deltas still worth re-sending after reconciling against [snapshot]. */
    fun reconcile(pending: List<SetEditDelta>, snapshot: WatchSnapshot): List<SetEditDelta> =
        pending.filter { delta ->
            delta.dayId == snapshot.day.dayId && !delta.isSettled(pending, snapshot)
        }

    /**
     * The swaps still worth re-sending after reconciling against [snapshot] (#90).
     *
     * A swap leaves the queue on any of four terminal conditions:
     *  - **Landed** — the snapshot's slot now *is* the exercise it asked for. Judged
     *    on [WatchExercise.exerciseId], never on the display name: two catalog entries
     *    can share a name (two custom exercises, or an alternate named like the lift
     *    it replaces), and a name match against an old-state snapshot would drop a
     *    swap that never happened, which is the one failure this queue exists to
     *    prevent. The name is only consulted when the phone publishes no id at all —
     *    the documented degradation for a publisher older than that field.
     *  - **Refused by drift** — see [isRefusedByDrift].
     *  - **Superseded** — a strictly newer swap for the same slot is still queued; the
     *    snapshot will answer that one instead.
     *  - **Day changed** — the phone has moved on, same rule as a set edit.
     */
    fun reconcileSwaps(pending: List<ExerciseSwapDelta>, snapshot: WatchSnapshot): List<ExerciseSwapDelta> =
        pending.filter { swap ->
            swap.dayId == snapshot.day.dayId && !swap.isSettled(pending, snapshot)
        }

    private fun ExerciseSwapDelta.isSettled(
        pending: List<ExerciseSwapDelta>,
        snapshot: WatchSnapshot,
    ): Boolean {
        val slot = snapshot.day.exercises.firstOrNull { it.programExerciseId == programExerciseId }
        val superseded = pending.any {
            it.editedAtMillis > editedAtMillis &&
                it.dayId == dayId &&
                it.programExerciseId == programExerciseId
        }
        return hasLanded(slot) || isRefusedByDrift(slot) || superseded
    }

    /** True when [slot] is now the exercise this swap asked for. */
    private fun ExerciseSwapDelta.hasLanded(slot: WatchExercise?): Boolean {
        if (slot == null) return false
        // A blank id is a publisher from before slot identity rode the wire; matching
        // the display name is all such a snapshot can offer, and is why the ambiguity
        // this field exists to remove is documented as a degradation, not a rule.
        if (slot.exerciseId.isBlank()) return slot.name == exerciseName
        return slot.exerciseId == exerciseId
    }

    /**
     * True when a **fresh** snapshot's prescription for the slot no longer offers the
     * exercise this swap asked for, and the swap has not landed.
     *
     * The snapshot is the authority document: its `alternates` are what the phone
     * would accept for that slot right now, re-derived from the same function that
     * validates an incoming swap. So a target that has fallen out of the list is one
     * the phone can only ever answer INVALID — a custom alternate the lifter deleted,
     * an equipment set they narrowed, a slot changed on the phone underneath us.
     * Without this the request would re-send until the day turned over, and the lift
     * would sit read-only on the wrist the whole time for an answer that is never
     * coming.
     *
     * Dropping it restores the lift with no further machinery: the snapshot that
     * revealed the drift has already replaced the local optimistic rename, and losing
     * the queue entry un-dims the lift back onto the phone's current prescription.
     *
     * An empty list settles it too, and correctly: a phone with no prescription for
     * the slot — including one too old to publish alternates, which drops the message
     * by path filter anyway — can never say yes.
     */
    private fun ExerciseSwapDelta.isRefusedByDrift(slot: WatchExercise?): Boolean {
        val prescription = slot?.alternates ?: return false
        return prescription.none { it.exerciseId == exerciseId }
    }

    /**
     * The strictly-monotonic issue rule for `editedAtMillis` (the phone's per-slot
     * dedupe key): two distinct edits stamped in the same wall-clock millisecond
     * must still order, or the phone drops the second as a replay. The caller
     * persists the returned value as the new last-issued stamp so process death
     * can't reissue an old one.
     */
    fun nextStamp(nowMillis: Long, lastIssuedMillis: Long): Long =
        maxOf(nowMillis, lastIssuedMillis + 1)

    /**
     * Field-by-field: every value this delta carries is either shown by the snapshot
     * or overwritten by a strictly newer pending edit to the same row. Field-aware on
     * purpose — a newer reps-only edit must not swallow a pending weight edit, and a
     * multi-field tick must not settle on one of its fields alone.
     */
    private fun SetEditDelta.isSettled(pending: List<SetEditDelta>, snapshot: WatchSnapshot): Boolean {
        val row = rowIn(snapshot)
        val newer = pending.filter { it.isNewerEditToSameRowAs(this) }
        fun overwritten(carries: (SetEditDelta) -> Boolean): Boolean = newer.any(carries)

        val wanted = weightLb // local: a cross-module val doesn't smart-cast
        val weightOk = wanted == null ||
            (row != null && abs(row.weightLb - wanted) < WEIGHT_EPSILON) ||
            overwritten { it.weightLb != null }
        val repsOk = reps == null || row?.reps == reps || overwritten { it.reps != null }
        val secondsOk = seconds == null || row?.seconds == seconds || overwritten { it.seconds != null }
        val doneOk = done == null || row?.done == done || overwritten { it.done != null }
        // startedAtMillis/completedAtMillis add no clause here — see the class note:
        // the snapshot can't show them, and the phone persists them atomically with
        // the fields it can, so they settle with those.
        return weightOk && repsOk && secondsOk && doneOk
    }

    /** The snapshot row this delta addresses, or null when the snapshot doesn't
     *  carry it (unknown exercise, missing partner track, index out of range) —
     *  nothing about the delta can be confirmed against a row that isn't there. */
    private fun SetEditDelta.rowIn(snapshot: WatchSnapshot): WatchSet? {
        val exercise = snapshot.day.exercises
            .firstOrNull { it.programExerciseId == programExerciseId } ?: return null
        val track = if (slot == SLOT_SS) exercise.ssSets else exercise.sets
        return track.getOrNull(setIndex)
    }

    private fun SetEditDelta.isNewerEditToSameRowAs(other: SetEditDelta): Boolean =
        editedAtMillis > other.editedAtMillis &&
            dayId == other.dayId &&
            programExerciseId == other.programExerciseId &&
            slot == other.slot &&
            setIndex == other.setIndex

    private const val SLOT_SS = "ss"
    private const val WEIGHT_EPSILON = 0.001
}
