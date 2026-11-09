package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure

/** Drops the last round: a WORK set together with the RECOVERY sets directly before it, back to the previous WORK
 *  set. The first WORK set's round has no RECOVERY in front unless the block starts with one. RECOVERY sets after the
 *  last WORK set, WARMUP and COOLDOWN stay. A block with one WORK set comes back unchanged. */
internal fun CardioBlock.withoutLastRound(): CardioBlock {
    val work = sets.indices.filter { sets[it].role == CardioRole.WORK }
    if (work.size < 2) return this
    val last = work.last()
    val previous = work[work.lastIndex - 1]
    return copy(sets = sets.filterIndexed { index, set ->
        index != last && !(index > previous && index < last && set.role == CardioRole.RECOVERY)
    })
}

/** This set with its end moved to [value] in its own measure and its aim, if any, moved by the same ratio, so the pace
 *  holds: aim = max(1, Math.round(aim × value ÷ old end)). */
internal fun CardioSetSpec.withEndValue(value: Int): CardioSetSpec {
    val old = when (val end = end) {
        is Measure.Time -> end.seconds
        is Measure.Distance -> end.metres
    }
    fun scaled(aim: Int) = maxOf(1, Math.round(aim.toDouble() * value / old).toInt())
    return copy(
        end = when (end) {
            is Measure.Time -> Measure.Time(value)
            is Measure.Distance -> Measure.Distance(value)
        },
        aim = when (val aim = aim) {
            is Measure.Time -> Measure.Time(scaled(aim.seconds))
            is Measure.Distance -> Measure.Distance(scaled(aim.metres))
            null -> null
        },
    )
}
