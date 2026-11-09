package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.math.floor

/** No planned session's counted total passes 110 % of the family's longest recent session (design §3.2 Run cap row;
 *  Frandsen 2025). Ladder days are exempt; the caller checks [CardioRule.ladderLevel]. */
object RunCap {
    const val METRES_GRID = 100
    const val SECONDS_GRID = 60

    /** The maxima over [sessions], each a list of one session's results; null when none is finished. A session is
     *  finished when it has a WORK set and every WORK set is finished ([FinishedRule.finished], or status FINISHED on
     *  an older row). */
    fun maxima(sessions: List<List<CardioSetResult>>): RecentMaxima? = sessions.mapNotNull { session ->
        val work = session.filter { it.spec.role == CardioRole.WORK }
        if (work.isEmpty() || work.any { !(FinishedRule.finished(it) ?: (it.status == CardioSetStatus.FINISHED)) }) {
            return@mapNotNull null
        }
        val counted = session.filter { it.spec.role == CardioRole.WORK || it.spec.role == CardioRole.RECOVERY }
        val metres = counted.map { result ->
            result.metresAtEnd ?: result.metres ?: (result.spec.end as? Measure.Distance)?.metres
                ?.takeIf { result.status == CardioSetStatus.FINISHED }
        }
        RecentMaxima(
            if (metres.all { it != null }) metres.sumOf { requireNotNull(it) } else null,
            counted.sumOf { it.secondsAtEnd ?: it.elapsedSeconds },
        )
    }.reduceOrNull { maximum, session -> maximum.max(session) }

    /** The maxima the cap compares against at run open: history when it has any, else the seed for 30 days from the
     *  plan's creation, else none (no cap). */
    fun anchor(history: CardioHistory, todayEpochDay: Long): RecentMaxima? = history.longest ?: history.seed?.takeIf {
        history.planCreatedEpochDay != null && todayEpochDay - history.planCreatedEpochDay < 30
    }

    /** 110 % of [maximum], rounded down to [grid] when that stays above [maximum], else the unrounded 110 %; never
     *  below [maximum], so a repeat always fits. */
    fun limit(maximum: Int, grid: Int): Int {
        val raw = maximum.toLong() * 11 / 10
        val rounded = raw / grid * grid
        return (if (rounded > maximum) rounded else raw).toInt()
    }

    /** [block] with its counted total brought within the limit: intervals drop rounds from the end (never a shorter
     *  rep), a single WORK set is shortened. Unchanged when it fits, when [anchor] is null, or when no unit compares. */
    fun capped(block: CardioBlock, anchor: RecentMaxima?, secondsPerMetre: Double?): CardioBlock {
        if (anchor == null) return block
        val work = block.sets.filter { it.role == CardioRole.WORK }
        if (work.isEmpty()) return block
        val counted = block.sets.filter { it.role == CardioRole.WORK || it.role == CardioRole.RECOVERY }
        val distances = counted.map { (it.end as? Measure.Distance)?.metres }
        val metres = if (distances.all { it != null }) distances.sumOf { requireNotNull(it) } else null
        val inMetres = metres != null && anchor.metres != null
        val maximum = if (inMetres) requireNotNull(anchor.metres) else anchor.seconds ?: return block
        val cap = limit(maximum, if (inMetres) METRES_GRID else SECONDS_GRID)
        fun value(set: CardioSetSpec): Double? = if (inMetres) {
            (set.end as? Measure.Distance)?.metres?.toDouble()
        } else when (val end = set.end) {
            is Measure.Time -> end.seconds.toDouble()
            is Measure.Distance -> (set.aim as? Measure.Time)?.seconds?.toDouble()
                ?: secondsPerMetre?.let { end.metres * it }
        }
        fun countedTotal(current: CardioBlock): Double? {
            val values = current.sets.filter { it.role == CardioRole.WORK || it.role == CardioRole.RECOVERY }.map { value(it) }
            return if (values.all { it != null }) values.sumOf { requireNotNull(it) } else null
        }
        val total = countedTotal(block) ?: return block
        if (total <= cap) return block
        if (work.size >= 2) {
            var next = block
            var remaining = work.size
            while (remaining > 1 && requireNotNull(countedTotal(next)) > cap) {
                next = next.withoutLastRound()
                remaining--
            }
            return next
        }
        val set = work.single()
        val workValue = requireNotNull(value(set))
        val budget = cap - (total - workValue)
        val endValue = when (val end = set.end) {
            is Measure.Time -> end.seconds
            is Measure.Distance -> end.metres
        }
        val grid = if (set.end is Measure.Distance) METRES_GRID else SECONDS_GRID
        val floored = floor(endValue * budget / workValue + 1e-9).toInt()
        val rounded = floored / grid * grid
        val newEnd = if (rounded > 0 &&
            total - workValue + requireNotNull(value(set.withEndValue(rounded))) > maximum
        ) rounded else floored
        if (newEnd < 1) return block
        return block.copy(sets = block.sets.map { if (it.role == CardioRole.WORK) it.withEndValue(newEnd) else it })
    }
}
