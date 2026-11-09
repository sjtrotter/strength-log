package cloud.trotter.log.strength.domain.progression

/** Epley, trusted only to ten reps: with reps r and reps in reserve k, e1RM = w × (1 + (r + k) / 30). */
object Estimate {
    const val MAX_REPS = 10

    /** Null when r + k > [MAX_REPS]; the set then anchors a load directly (see Progression's calibration). */
    fun e1rm(weightLb: Double, reps: Int, rir: Int): Double? =
        if (reps + rir > MAX_REPS) null else weightLb * (1 + (reps + rir) / 30.0)

    /** The unrounded load for [reps] at [rir] reps in reserve. */
    fun loadFor(e1rmLb: Double, reps: Int, rir: Int): Double = e1rmLb / (1 + (reps + rir) / 30.0)
}
