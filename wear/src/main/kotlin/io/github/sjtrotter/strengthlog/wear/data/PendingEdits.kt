package io.github.sjtrotter.strengthlog.wear.data

import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import kotlin.math.abs

/**
 * The watch's outbound-queue drain rule, kept pure so §11.4's "an edit survives a
 * phone-app restart" is JVM-testable without a device.
 *
 * MessageClient is fire-and-forget, so [io.github.sjtrotter.strengthlog.wear.data.DataLayerWatchClient]
 * queues every edit and re-sends it until the phone's next snapshot *reflects* it —
 * the round-trip snapshot is the only ack (spec §9, last-write-wins). Because the
 * phone dedupes replays by `editedAtMillis`, re-sending an already-applied delta is
 * harmless, so the queue can safely resend everything still pending on any signal.
 *
 * A queued delta settles (drops out of the queue) when any of:
 *  - the snapshot's addressed row already shows the delta's requested values;
 *  - the snapshot is for a *different* suggested day — the phone has moved on
 *    (the day was completed/advanced), so an edit to the old day can neither be
 *    confirmed nor should keep resending; last-write-wins lets it go;
 *  - a strictly newer pending delta to the same row overwrites every field it
 *    carries — the snapshot will only ever reflect the newer value, so the
 *    superseded delta could never settle and would re-send forever (the phone
 *    safely drops it as stale, but the queue would never drain).
 */
object PendingEdits {

    /** The deltas still worth re-sending after reconciling against [snapshot]. */
    fun reconcile(pending: List<SetEditDelta>, snapshot: WatchSnapshot): List<SetEditDelta> =
        pending.filter { delta ->
            delta.dayId == snapshot.day.dayId &&
                !delta.isReflectedIn(snapshot) &&
                pending.none { it.supersedes(delta) }
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

    /** True when this delta makes [older] pointless: same row, strictly newer, and
     *  every field [older] carries is overwritten. Field-aware on purpose — a newer
     *  reps-only edit must not swallow a pending weight edit to the same row. */
    private fun SetEditDelta.supersedes(older: SetEditDelta): Boolean =
        editedAtMillis > older.editedAtMillis &&
            dayId == older.dayId &&
            programExerciseId == older.programExerciseId &&
            slot == older.slot &&
            setIndex == older.setIndex &&
            (older.weightLb == null || weightLb != null) &&
            (older.reps == null || reps != null) &&
            (older.seconds == null || seconds != null) &&
            (older.done == null || done != null)

    // Settle edge: this checks every carried field together, which is exact only
    // while the UI emits single-field deltas (it does today). If the watch ever
    // sends multi-field deltas, settling must become per-field — each carried
    // field reflected OR overwritten by a newer pending delta carrying it.
    private fun SetEditDelta.isReflectedIn(snapshot: WatchSnapshot): Boolean {
        val exercise = snapshot.day.exercises
            .firstOrNull { it.programExerciseId == programExerciseId } ?: return false
        val track = if (slot == SLOT_SS) exercise.ssSets else exercise.sets
        val set = track.getOrNull(setIndex) ?: return false
        val weightOk = weightLb?.let { abs(set.weightLb - it) < WEIGHT_EPSILON } ?: true
        val repsOk = reps?.let { set.reps == it } ?: true
        val secondsOk = seconds?.let { set.seconds == it } ?: true
        val doneOk = done?.let { set.done == it } ?: true
        return weightOk && repsOk && secondsOk && doneOk
    }

    private const val SLOT_SS = "ss"
    private const val WEIGHT_EPSILON = 0.001
}
