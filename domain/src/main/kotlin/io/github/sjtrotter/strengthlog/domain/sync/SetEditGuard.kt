package io.github.sjtrotter.strengthlog.domain.sync

import io.github.sjtrotter.strengthlog.domain.library.TrackingType

/**
 * The load-bearing mixed-version guard (tracking-types design risk #2): a watch
 * edit must only touch fields its exercise actually tracks. A stale/old watch —
 * one that pre-dates the reclassification and still draws a weight stepper for a
 * plank, or a reps row for a hold — can emit a semantically-wrong delta; the
 * phone must drop the dead fields before writing so such an edit can never corrupt
 * a log.
 *
 * Kept pure (domain, no Android) so every ignore/keep case is JVM-testable. The
 * `done` tick is always allowed — every type has a checkmark. The kept fields per
 * type:
 *
 * | tracking | weightLb | reps | seconds |
 * |----------|----------|------|---------|
 * | WEIGHTED | keep     | keep | drop    |
 * | REPS     | drop     | keep | drop    |
 * | TIMED    | drop     | drop | keep    |
 *
 * TIMED drops `weightLb` deliberately: a weighted hold's added load is a phone-side
 * setup value, not a wrist edit, and — crucially — an old watch's bogus plank-weight
 * delta is indistinguishable from a "real" one, so the only safe rule is to never
 * accept weight on a TIMED track. The watch renders added load read-only to match.
 */
fun SetEditDelta.guardedFor(tracking: TrackingType): SetEditDelta = when (tracking) {
    TrackingType.WEIGHTED -> copy(seconds = null)
    TrackingType.REPS -> copy(weightLb = null, seconds = null)
    TrackingType.TIMED -> copy(weightLb = null, reps = null)
}
