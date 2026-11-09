package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.progression.CardioMeasures
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.distanceMetres
import cloud.trotter.log.strength.domain.progression.timeSeconds

/** The size of an offered change, read from the first WORK set; the app formats it. */
sealed interface CardioIncrease {
    data class Distance(val metres: Int) : CardioIncrease
    data class Time(val seconds: Int) : CardioIncrease
    data class Faster(val seconds: Int) : CardioIncrease
}

/** Next time for a cardio block: derived on every read, like strength's NextTime. */
sealed interface CardioNextTime {
    data object Repeat : CardioNextTime
    data class Offer(
        val offered: CardioBlock,
        val opening: CardioBlock,
        val applied: Boolean,
        val change: CardioIncrease,
    ) : CardioNextTime
}

/** Superseded by [Progression.cardioAfterSession]; deleted when Finish switches over (PR 2b). */
object CardioProgression {
    const val MIN_PERCENT = CardioMeasures.MIN_PERCENT
    const val MAX_PERCENT = CardioMeasures.MAX_PERCENT

    fun defaultPercent(measure: ProgressMeasure): Int = CardioMeasures.defaultPercent(measure)

    fun available(block: CardioBlock): Set<ProgressMeasure> = CardioMeasures.available(block)

    fun defaultMeasure(block: CardioBlock): ProgressMeasure? = CardioMeasures.defaultMeasure(block)

    fun resolve(block: CardioBlock, stored: ProgressMeasure?): ProgressMeasure? = CardioMeasures.resolve(block, stored)

    fun increased(block: CardioBlock, measure: ProgressMeasure, percent: Int): CardioBlock = CardioMeasures.increased(block, measure, percent)

    fun fullSuccess(block: CardioBlock, results: List<CardioSetResult>): Boolean = CardioMeasures.fullSuccess(block, results)

    /** Offer a changed block only after full success; misses never reduce targets. */
    fun offer(block: CardioBlock, results: List<CardioSetResult>, measure: ProgressMeasure, percent: Int): CardioBlock? =
        if (fullSuccess(block, results)) increased(block, measure, percent).takeIf { it != block } else null

    /** Compare the current block with the last opening and offer to derive repeat, offered, or applied state. */
    fun nextTime(
        current: CardioBlock,
        lastActivity: CardioActivity?,
        last: List<CardioSetResult>?,
        measure: ProgressMeasure?,
        percent: Int,
    ): CardioNextTime? {
        if (last.isNullOrEmpty() || measure == null || lastActivity != current.activity) return null
        val opening = try {
            CardioBlock(current.activity, last.map { it.spec })
        } catch (_: IllegalArgumentException) {
            return null
        }
        val offered = offer(opening, last, measure, percent)
            ?: return CardioNextTime.Repeat.takeIf { current.sets == opening.sets }
        val applied = when (current.sets) {
            opening.sets -> false
            offered.sets -> true
            else -> return null
        }
        val first = opening.sets.indexOfFirst { it.role == CardioRole.WORK }
        val before = opening.sets[first]
        val after = offered.sets[first]
        val change = when (measure) {
            ProgressMeasure.DISTANCE -> CardioIncrease.Distance(after.distanceMetres() - before.distanceMetres())
            ProgressMeasure.TIME -> CardioIncrease.Time(after.timeSeconds() - before.timeSeconds())
            ProgressMeasure.PACE -> when (before.end) {
                is Measure.Distance -> CardioIncrease.Faster(before.timeSeconds() - after.timeSeconds())
                is Measure.Time -> CardioIncrease.Distance(after.distanceMetres() - before.distanceMetres())
            }
        }
        return CardioNextTime.Offer(offered, opening, applied, change)
    }
}
