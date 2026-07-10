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
 * A queued delta settles (drops out of the queue) when either:
 *  - the snapshot's addressed row already shows the delta's requested values, or
 *  - the snapshot is for a *different* suggested day — the phone has moved on
 *    (the day was completed/advanced), so an edit to the old day can neither be
 *    confirmed nor should keep resending; last-write-wins lets it go.
 */
object PendingEdits {

    /** The deltas still worth re-sending after reconciling against [snapshot]. */
    fun reconcile(pending: List<SetEditDelta>, snapshot: WatchSnapshot): List<SetEditDelta> =
        pending.filter { it.dayId == snapshot.day.dayId && !it.isReflectedIn(snapshot) }

    private fun SetEditDelta.isReflectedIn(snapshot: WatchSnapshot): Boolean {
        val exercise = snapshot.day.exercises
            .firstOrNull { it.programExerciseId == programExerciseId } ?: return false
        val track = if (slot == SLOT_SS) exercise.ssSets else exercise.sets
        val set = track.getOrNull(setIndex) ?: return false
        val weightOk = weightLb?.let { abs(set.weightLb - it) < WEIGHT_EPSILON } ?: true
        val repsOk = reps?.let { set.reps == it } ?: true
        val doneOk = done?.let { set.done == it } ?: true
        return weightOk && repsOk && doneOk
    }

    private const val SLOT_SS = "ss"
    private const val WEIGHT_EPSILON = 0.001
}
