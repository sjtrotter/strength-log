package io.github.sjtrotter.strengthlog.domain.units

/**
 * Display unit for a weight. Canonical storage is always pounds: every spec
 * calculation (goal math, ramps, cascades, [WeightStepper.round]'s spiritual
 * ancestor round5) is lb-calibrated and stays that way regardless of what the
 * user sees on screen. This is the one conversion point (SSOT) between the
 * two — nothing else in the codebase should do lb/kg math directly.
 */
enum class WeightUnit {
    LB,
    KG;

    /** Converts a canonical lb value to this unit's display value. */
    fun fromLb(lb: Double): Double = when (this) {
        LB -> lb
        KG -> lb / LB_PER_KG
    }

    /** Converts a display value in this unit back to canonical lb. */
    fun toLb(display: Double): Double = when (this) {
        LB -> display
        KG -> display * LB_PER_KG
    }

    companion object {
        /** 1 kg = 2.2046226218 lb. */
        const val LB_PER_KG = 2.2046226218
    }
}
