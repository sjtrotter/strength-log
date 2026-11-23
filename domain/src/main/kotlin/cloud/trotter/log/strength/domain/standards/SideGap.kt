package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Side
import cloud.trotter.log.strength.domain.model.SideResult

/** No measured noise threshold exists for these lifts; three sessions stand in for it (#644).
 * Wording stays neutral because asymmetry has not been shown to predict injury (Helme 2021). */
object SideGap {
    const val SESSIONS = 3

    fun lowerSide(sets: List<LoggedSet>): Side? {
        val sided = sets.filter { it.sides != null }
        if (sided.isEmpty()) return null
        var left = 0.0
        var right = 0.0
        for (set in sided) {
            val sides = requireNotNull(set.sides)
            val tracking = SetFormatter.trackingOfSet(set)
            left += measure(sides.left, tracking)
            right += measure(sides.right, tracking)
        }
        return when {
            left < right -> Side.LEFT
            right < left -> Side.RIGHT
            else -> null
        }
    }

    /** [sessions] newest first, each one session's done sets of the exercise. */
    fun repeated(sessions: List<List<LoggedSet>>): Side? {
        if (sessions.size < SESSIONS) return null
        val lower = sessions.take(SESSIONS).map(::lowerSide)
        return lower.first()?.takeIf { side -> lower.all { it == side } }
    }

    private fun measure(side: SideResult, tracking: TrackingType): Double = when (tracking) {
        TrackingType.TIMED -> side.seconds.toDouble()
        TrackingType.REPS -> side.reps.toDouble()
        TrackingType.WEIGHTED -> side.weightLb * side.reps
    }
}
