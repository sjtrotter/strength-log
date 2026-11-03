package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.SetFormatter

enum class PrKind { WEIGHT, REPS, HOLD, FASTER, FARTHER }

/**
 * Single source for personal bests read by the receipt, History, share card and Best chip.
 * Only done, non-RAMP sets with a positive weight, rep count or duration count.
 * Tracking comes from values; oldest-first groups use their newest counting set's tracking type.
 * A lift retyped back to an earlier type keeps that type's older history.
 * Rank WEIGHTED by weight then reps, REPS by reps, and TIMED by seconds then added weight.
 * A PR strictly beats a non-null standing best of the same type; first times and ties are not PRs.
 */
object PersonalBests {

    fun counts(set: LoggedSet): Boolean =
        set.done && set.kind != SetKind.RAMP && (set.weightLb > 0.0 || set.reps > 0 || set.seconds > 0)

    fun tracking(set: LoggedSet): TrackingType =
        SetFormatter.trackingOfValues(set.weightLb, set.reps, set.seconds)

    /** Requires [a] and [b] to have the same value-derived tracking type. */
    fun compare(a: LoggedSet, b: LoggedSet): Int {
        val type = tracking(a)
        require(type == tracking(b)) { "Cannot compare sets with different tracking types" }
        return when (type) {
            TrackingType.WEIGHTED -> compareValuesBy(a, b, { it.weightLb }, { it.reps })
            TrackingType.REPS -> a.reps.compareTo(b.reps)
            TrackingType.TIMED -> compareValuesBy(a, b, { it.seconds }, { it.weightLb })
        }
    }

    fun bestIndex(sets: List<LoggedSet>): Int? {
        val type = sets.lastOrNull(::counts)?.let(::tracking) ?: return null
        var bestIndex: Int? = null
        for ((index, set) in sets.withIndex()) {
            if (!counts(set) || tracking(set) != type) continue
            val current = bestIndex
            if (current == null || compare(set, sets[current]) > 0) {
                bestIndex = index
            }
        }
        return bestIndex
    }

    fun best(sets: List<LoggedSet>): LoggedSet? = bestIndex(sets)?.let(sets::get)

    fun prKind(candidate: LoggedSet, standing: LoggedSet?): PrKind? {
        if (!counts(candidate) || standing == null ||
            tracking(candidate) != tracking(standing) || compare(candidate, standing) <= 0
        ) return null

        return when (tracking(candidate)) {
            TrackingType.WEIGHTED -> if (candidate.weightLb > standing.weightLb) PrKind.WEIGHT else PrKind.REPS
            TrackingType.REPS -> PrKind.REPS
            TrackingType.TIMED -> if (candidate.seconds > standing.seconds) PrKind.HOLD else PrKind.WEIGHT
        }
    }
}
