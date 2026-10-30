package cloud.trotter.log.strength.data.migration

import cloud.trotter.log.strength.domain.model.LoggedSet

/**
 * The reps→seconds reinterpretation for a legacy live-log set belonging to an
 * entry reclassified to TIMED. Legacy timed holds store their duration in reps;
 * that value belongs in [LoggedSet.seconds].
 *
 * Guarded to an *unfixed* row (`seconds == 0 && reps > 0`) so the fixup is
 * idempotent at the row level: a set already carrying seconds (a restored v2
 * backup, or a second accidental run) is left exactly as-is and its hold can
 * never be clobbered back to zero. Nothing is ever deleted.
 */
fun LoggedSet.reinterpretRepsAsSeconds(): LoggedSet =
    if (seconds == 0 && reps > 0) copy(seconds = reps, reps = 0) else this
