package cloud.trotter.log.strength.domain.units

/**
 * Display unit for a weight. Canonical storage is always pounds. Generated
 * loads snap to the selected unit's grid before storage; conversion lives here
 * so nothing else in the codebase does lb/kg math directly.
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
