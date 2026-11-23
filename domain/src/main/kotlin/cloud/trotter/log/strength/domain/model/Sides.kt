package cloud.trotter.log.strength.domain.model

/** A side at zero reps, or zero seconds for a hold, was not done. */
data class SideResult(val weightLb: Double, val reps: Int, val seconds: Int)

/** The single values are the field-wise minima, so both sides must reach a reader's target (#644). */
data class Sides(val left: SideResult, val right: SideResult) {
    val weightLb: Double get() = minOf(left.weightLb, right.weightLb)
    val reps: Int get() = minOf(left.reps, right.reps)
    val seconds: Int get() = minOf(left.seconds, right.seconds)
    val valid: Boolean
        get() = listOf(left, right).all { it.weightLb.isFinite() && it.weightLb >= 0.0 && it.reps >= 0 && it.seconds >= 0 }
}

/** True without sides; every write and import keeps this agreement. */
val LoggedSet.sidesAgree: Boolean
    get() = sides?.let { weightLb == it.weightLb && reps == it.reps && seconds == it.seconds } ?: true

/** A shared value moves on both sides; a change to a differing value drops sides rather than inventing a split. */
fun LoggedSet.sidesAfter(after: LoggedSet): Sides? {
    val current = sides ?: return null
    fun <T> moved(left: T, right: T, from: T, to: T): Pair<T, T>? = when {
        to == from -> left to right
        left == right -> to to to
        else -> null
    }
    val w = moved(current.left.weightLb, current.right.weightLb, weightLb, after.weightLb) ?: return null
    val r = moved(current.left.reps, current.right.reps, reps, after.reps) ?: return null
    val s = moved(current.left.seconds, current.right.seconds, seconds, after.seconds) ?: return null
    return Sides(SideResult(w.first, r.first, s.first), SideResult(w.second, r.second, s.second))
}
