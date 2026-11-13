package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.SprintAttempt
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import kotlin.math.abs

/** One series of timed sprint reps (design §3.1.3): times compare only within one distance, timing method and start
 *  protocol, with no conversion between methods. */
data class SprintSeries(val metres: Int, val timingMethod: TimingMethod, val startProtocol: StartProtocol)

enum class SprintVerdict { FASTER, SAME, SLOWER }

object SprintComparison {
    /** Hand-timed differences under 0.1 s read as the same (design §3.1.3); other methods have no threshold. */
    const val HAND_SAME_CENTIS = 10

    /** The series of a FINISHED, timed WORK set with a distance end; null for any other set. */
    fun series(result: CardioSetResult): SprintSeries? {
        if (result.status != CardioSetStatus.FINISHED || result.spec.role != CardioRole.WORK) return null
        val end = result.spec.end as? Measure.Distance ?: return null
        val time = result.timing ?: return null
        return SprintSeries(end.metres, time.timingMethod, time.startProtocol)
    }

    /** [new] against [standing]. Both must share a timing method and start protocol. */
    fun verdict(new: SprintTime, standing: SprintTime): SprintVerdict {
        require(new.timingMethod == standing.timingMethod && new.startProtocol == standing.startProtocol)
        val difference = standing.centis - new.centis
        return when {
            new.timingMethod == TimingMethod.HAND && abs(difference) < HAND_SAME_CENTIS -> SprintVerdict.SAME
            difference > 0 -> SprintVerdict.FASTER
            difference < 0 -> SprintVerdict.SLOWER
            else -> SprintVerdict.SAME
        }
    }

    /** Per series, the standing best after [results] in order: a time replaces it only when FASTER. */
    fun bests(results: List<CardioSetResult>): Map<SprintSeries, CardioSetResult> {
        val bests = linkedMapOf<SprintSeries, CardioSetResult>()
        for (result in results) {
            val series = series(result) ?: continue
            val standing = bests[series]
            if (standing == null || verdict(requireNotNull(result.timing), requireNotNull(standing.timing)) == SprintVerdict.FASTER) {
                bests[series] = result
            }
        }
        return bests
    }

    /** A time-trial session's fastest rep per series at [goal]'s distance, first on a tie. */
    fun attempts(goal: PlanGoal.Sprint, results: List<CardioSetResult>, completedAt: Long): List<SprintAttempt> {
        val fastest = linkedMapOf<SprintSeries, SprintTime>()
        for (result in results) {
            val series = series(result)?.takeIf { it.metres == goal.metres } ?: continue
            val time = requireNotNull(result.timing)
            val standing = fastest[series]
            if (standing == null || time.centis < standing.centis) fastest[series] = time
        }
        return fastest.values.map { SprintAttempt(completedAt, it) }
    }
}
