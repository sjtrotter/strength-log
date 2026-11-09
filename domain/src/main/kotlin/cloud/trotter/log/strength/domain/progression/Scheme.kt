package cloud.trotter.log.strength.domain.progression

/** Ramps and back-offs around the TOP set of a ramped structure; STRAIGHT slots ignore it. */
data class Scheme(val ramps: List<Ramp>, val backoffs: Int, val backoffPct: Double, val backoffReps: Int) {
    data class Ramp(val pct: Double, val reps: Int)

    companion object {
        /** The prototype's numbers. Converted plans keep them, so their stored rows and the TOP cascade
         *  stay what spec §11 pins. */
        val PROTOTYPE = Scheme(
            listOf(Ramp(0.55, 5), Ramp(0.70, 5), Ramp(0.80, 5), Ramp(0.90, 3)),
            backoffs = 1, backoffPct = 0.75, backoffReps = 8,
        )

        /** The Strength main lift, A year or more (design §3.1.1): ramps 60 % × 5 and 80 % × 3, two back-offs at 90 % × 5. */
        val STRENGTH = Scheme(listOf(Ramp(0.60, 5), Ramp(0.80, 3)), backoffs = 2, backoffPct = 0.90, backoffReps = 5)

        fun round5(w: Double) = maxOf(5.0, Math.round(w / 5.0) * 5.0)
    }
}
