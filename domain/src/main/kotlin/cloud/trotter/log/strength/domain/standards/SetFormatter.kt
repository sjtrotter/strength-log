package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.units.SecondsStepper
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit

/**
 * The single place a *performed* set's actuals (weight/reps/seconds) become a
 * summary string, dispatched on its exercise's [TrackingType] (SSOT, design
 * risk #3). The "last time"/Best chips, collapsed set summaries and history rows
 * all format here so no surface can invent a "0 lb × 60" rendering for a
 * REPS/TIMED entry — a REPS best never reads as "0 lb", a TIMED best reads as
 * its hold, not "25×0".
 *
 * WEIGHTED output ("225×5") is byte-identical to the ad-hoc `w×r` form the
 * day/log builders used before this helper existed, so wiring an existing
 * surface through it changes nothing for a weighted exercise. The companion
 * [GoalFormatter] formats read-only GOAL targets the same three ways.
 */
object SetFormatter {

    /** The `w×r` / `×r` / `Ns (+load)` summary for one performed set in [unit]. */
    fun summary(
        tracking: TrackingType,
        weightLb: Double,
        reps: Int,
        seconds: Int,
        unit: WeightUnit,
    ): String = when (tracking) {
        TrackingType.WEIGHTED -> "${WeightStepper.format(unit.fromLb(weightLb))}×$reps"
        TrackingType.REPS -> "×$reps"
        TrackingType.TIMED ->
            if (weightLb > 0.0) {
                "${SecondsStepper.format(seconds)} +${WeightStepper.format(unit.fromLb(weightLb))}"
            } else {
                SecondsStepper.format(seconds)
            }
    }

    /**
     * The [TrackingType] implied by a performed set's own logged values, for
     * surfaces that can't trust the exercise's *current* tracking type (design
     * risk #3, P3 advisory #2): a `session_set` history row logged before a
     * reclassification can be legacy reps-shaped even though the exercise
     * tracks TIMED today, because the P3 fixup only ever touched live
     * `exercise_log` rows, never history. A hold (`seconds > 0`) is always a
     * hold; a zero-weight rep count is always reps; everything else is
     * weight×reps. This is what keeps a legacy plank "Best" from ever reading
     * "0s" — it reads its logged rep count instead. Exposed on its own (not
     * just inlined in [summaryOfValues]) so a caller that needs the
     * classification without a formatted string — the session share card
     * picking a lift's "heaviest" set — shares this same rule (SSOT).
     */
    fun trackingOfValues(weightLb: Double, reps: Int, seconds: Int): TrackingType = when {
        seconds > 0 -> TrackingType.TIMED
        weightLb == 0.0 && reps > 0 -> TrackingType.REPS
        else -> TrackingType.WEIGHTED
    }

    /** The set's VALUE-driven summary — see [trackingOfValues] for the classification rule. */
    fun summaryOfValues(weightLb: Double, reps: Int, seconds: Int, unit: WeightUnit): String =
        summary(trackingOfValues(weightLb, reps, seconds), weightLb, reps, seconds, unit)
}
