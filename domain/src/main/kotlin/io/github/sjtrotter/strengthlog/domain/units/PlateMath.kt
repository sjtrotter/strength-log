package io.github.sjtrotter.strengthlog.domain.units

/**
 * Derives what to load on a barbell from a display weight — the plates a
 * lifter would actually put on one side, heaviest first. Pure arithmetic on
 * values already expressed in a [WeightUnit]; nothing here touches storage.
 */
object PlateMath {

    // 35 lb is deliberately absent: including it in the greedy pass breaks
    // the spec-pinned 130 -> 25/10/5/2.5 and 210 -> 45/25/10/2.5 vectors
    // (greedy would reach for 35 before those), and it's the least commonly
    // stocked plate at a gym anyway.
    private val LB_PLATES = listOf(45.0, 25.0, 10.0, 5.0, 2.5)
    private val KG_PLATES = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)
    private const val EPSILON = 1e-9

    /** The bar the standard denominations assume: 45 lb / 20 kg. */
    fun barWeight(unit: WeightUnit): Double = when (unit) {
        WeightUnit.LB -> 45.0
        WeightUnit.KG -> 20.0
    }

    /**
     * Plates on ONE side for [displayWeight] (already in [unit]), heaviest
     * first — or null when the weight can't be loaded exactly: below the bar,
     * or leaves a remainder no standard plate covers. An empty list is a
     * valid result: the empty bar.
     */
    fun perSide(displayWeight: Double, unit: WeightUnit): List<Double>? {
        val bar = barWeight(unit)
        if (displayWeight < bar - EPSILON) return null

        var remaining = (displayWeight - bar) / 2.0
        val denominations = when (unit) {
            WeightUnit.LB -> LB_PLATES
            WeightUnit.KG -> KG_PLATES
        }

        val plates = mutableListOf<Double>()
        for (plate in denominations) {
            while (remaining >= plate - EPSILON) {
                plates += plate
                remaining -= plate
            }
        }

        return if (remaining > EPSILON) null else plates
    }
}
