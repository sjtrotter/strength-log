package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import kotlin.math.ceil

/** The taper from the live goal date: WORK volume scales, hard flags and pace stay.
 *  Audit 2026-11-13 row 15 (R32/R33; Bosquet 2007, Wang 2023; weak) targets a 41–60 percent mean cut.
 *  Whether Bosquet's cut means the taper mean or the final week is unverified; these factors assume the mean.
 *  The half-marathon split is practice; lengths and intensity are unchanged. */
object Taper {
    /** Upper edges of the goal-distance bands, in metres: up to 10K (and every test), then up to the half. */
    const val TEN_K_METRES = 10_000
    const val HALF_METRES = 21_100

    /** The WORK scale on [todayEpochDay], or null outside a taper. */
    fun factor(goal: PlanGoal?, todayEpochDay: Long): Double? {
        if (goal == null) return null
        val daysOut = (goal.byEpochDay ?: return null) - todayEpochDay
        if (daysOut < 0) return null
        return when (goal) {
            is PlanGoal.Sprint -> null
            is PlanGoal.Shuttle -> if (daysOut <= 10) 0.5 else null
            is PlanGoal.Timed -> when {
                goal.metres <= TEN_K_METRES -> if (daysOut <= 10) 0.5 else null
                goal.metres <= HALF_METRES -> when {
                    daysOut <= 7 -> 0.45
                    daysOut <= 14 -> 0.6
                    else -> null
                }
                else -> when {
                    daysOut <= 7 -> 0.4
                    daysOut <= 14 -> 0.55
                    daysOut <= 21 -> 0.75
                    else -> null
                }
            }
        }
    }

    /** [block] scaled by [factor]: a single WORK set's end × factor (pace held); with two or more WORK sets, the first
     *  ceil(n × factor) rounds, at least one, reps untouched. */
    fun applied(block: CardioBlock, factor: Double): CardioBlock {
        val work = block.sets.filter { it.role == CardioRole.WORK }
        if (work.isEmpty()) return block
        if (work.size == 1) {
            val set = work.single()
            val end = when (val end = set.end) {
                is Measure.Time -> end.seconds
                is Measure.Distance -> end.metres
            }
            return block.copy(sets = block.sets.map {
                if (it.role == CardioRole.WORK) it.withEndValue(maxOf(1, Math.round(end * factor).toInt())) else it
            })
        }
        val keep = maxOf(1, ceil(work.size * factor - 1e-9).toInt())
        var next = block
        repeat((work.size - keep).coerceAtLeast(0)) { next = next.withoutLastRound() }
        return next
    }
}
