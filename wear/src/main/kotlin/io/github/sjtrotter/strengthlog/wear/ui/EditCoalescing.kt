package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.wear.data.WatchEditOptimism

/**
 * The crown/±-button edit coalescer, kept pure so the "one delta per settle, not
 * one per detent" contract (the crown-spam bug) is JVM-testable off-device.
 *
 * The exercise-stream screen holds a small list of *pending* single-field edits.
 * A rapid crown twist produces a detent per frame; instead of sending one
 * [SetEditDelta] each, the screen folds every detent into the pending list via
 * [mergePending] — a later edit to the same field replaces the earlier one — and
 * only drains the list (one delta per touched field, carrying the final value)
 * when the user pauses, ticks, backs out, or the app stops. The big numeral still
 * responds live because [applyPendingOverlay] shows the pending values on top of
 * the last snapshot, so nothing waits on the outbound round-trip.
 *
 * Field-scoped on purpose: a pending reps edit must not swallow a pending weight
 * edit to the same round, so [coalesceKey] keys on (slot, setIndex, field). The
 * UI only ever emits single-field deltas, which is what makes the key well-defined.
 */

/** The field a single-field pending delta carries — the coalescing granularity. */
private fun SetEditDelta.fieldTag(): String = when {
    weightLb != null -> "W"
    reps != null -> "R"
    done != null -> "D"
    else -> "?"
}

/** Stable identity of the value stream a delta belongs to: one field of one round of one track. */
fun coalesceKey(delta: SetEditDelta): String = "${delta.slot}|${delta.setIndex}|${delta.fieldTag()}"

/**
 * Folds [next] into [pending], replacing any earlier edit to the same field so a
 * burst collapses to the single latest value. Distinct fields (weight vs reps,
 * main vs partner, different rounds) coexist. Order is preserved with the newest
 * entry moved to the end.
 */
fun mergePending(pending: List<SetEditDelta>, next: SetEditDelta): List<SetEditDelta> {
    val key = coalesceKey(next)
    return pending.filterNot { coalesceKey(it) == key } + next
}

/**
 * The exercise as it should render right now: the last snapshot with every
 * pending edit overlaid, so the on-screen numerals track the crown live before
 * any delta is sent. Reuses [WatchEditOptimism] (SSOT with the client's own echo)
 * and is a no-op when [pending] is empty.
 */
fun applyPendingOverlay(exercise: WatchExercise, pending: List<SetEditDelta>): WatchExercise =
    pending.fold(exercise) { ex, delta ->
        WatchEditOptimism.apply(listOf(ex), delta).first()
    }
