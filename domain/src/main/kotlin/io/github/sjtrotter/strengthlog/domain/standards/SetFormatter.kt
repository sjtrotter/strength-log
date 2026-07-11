package io.github.sjtrotter.strengthlog.domain.standards

import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

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
            if (weightLb > 0.0) "${seconds}s +${WeightStepper.format(unit.fromLb(weightLb))}" else "${seconds}s"
    }
}
