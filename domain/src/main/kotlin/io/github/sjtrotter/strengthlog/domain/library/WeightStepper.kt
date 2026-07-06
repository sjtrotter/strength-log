package io.github.sjtrotter.strengthlog.domain.library

/** Weight-stepper granularity in lb (spec §5). Reps stepper is always ±1. */
object WeightStepper {
    fun incrementLb(currentWeightLb: Double): Double =
        if (currentWeightLb <= 20.0) 2.5 else 5.0
}
