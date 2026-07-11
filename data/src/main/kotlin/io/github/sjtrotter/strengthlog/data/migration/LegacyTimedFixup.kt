package io.github.sjtrotter.strengthlog.data.migration

import io.github.sjtrotter.strengthlog.domain.model.LoggedSet

/**
 * The reps→seconds reinterpretation for a live-log set belonging to an entry that
 * was reclassified to TIMED (tracking-types P3, Decision 5). Before the update the
 * only per-set field the UI offered was reps, so a user timing a plank recorded
 * the seconds there; now that the entry tracks time, that value belongs in
 * [LoggedSet.seconds].
 *
 * Guarded to an *unfixed* row (`seconds == 0 && reps > 0`) so the fixup is
 * idempotent at the row level: a set already carrying seconds (a restored v2
 * backup, or a second accidental run) is left exactly as-is and its hold can
 * never be clobbered back to zero. Nothing is ever deleted.
 */
fun LoggedSet.reinterpretRepsAsSeconds(): LoggedSet =
    if (seconds == 0 && reps > 0) copy(seconds = reps, reps = 0) else this
