package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure

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

object CardioProgression {
    const val MIN_PERCENT = 1
    const val MAX_PERCENT = 25

    /** Distance and time default to five percent; opt-in pace defaults to one percent. */
    fun defaultPercent(measure: ProgressMeasure): Int = when (measure) {
        ProgressMeasure.DISTANCE, ProgressMeasure.TIME -> 5
        ProgressMeasure.PACE -> 1
    }

    /** A measure is available only when every WORK set supports it. */
    fun available(block: CardioBlock): Set<ProgressMeasure> {
        val work = block.sets.filter { it.role == CardioRole.WORK }
        if (work.isEmpty()) return emptySet()
        return buildSet {
            if (work.all { it.end is Measure.Distance || it.aim is Measure.Distance }) add(ProgressMeasure.DISTANCE)
            if (work.all { it.end is Measure.Time }) add(ProgressMeasure.TIME)
            if (work.all { it.aim != null }) add(ProgressMeasure.PACE)
        }
    }

    /** Prefer distance ends, then time, then distance aims; pace is never a default. */
    fun defaultMeasure(block: CardioBlock): ProgressMeasure? {
        val available = available(block)
        return when {
            ProgressMeasure.DISTANCE in available &&
                block.sets.filter { it.role == CardioRole.WORK }.all { it.end is Measure.Distance } -> ProgressMeasure.DISTANCE
            ProgressMeasure.TIME in available -> ProgressMeasure.TIME
            ProgressMeasure.DISTANCE in available -> ProgressMeasure.DISTANCE
            else -> null
        }
    }

    /** Keep a supported stored choice, otherwise use the block's default. */
    fun resolve(block: CardioBlock, stored: ProgressMeasure?): ProgressMeasure? =
        stored?.takeIf { it in available(block) } ?: defaultMeasure(block)

    /** Increase only WORK targets, preserving pace for distance or time; reject unsupported or out-of-range changes. */
    fun increased(block: CardioBlock, measure: ProgressMeasure, percent: Int): CardioBlock {
        if (measure !in available(block)) return block
        val clamped = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        fun step(value: Int) = maxOf(1, Math.round(value * clamped / 100.0).toInt())
        return try {
            block.copy(sets = block.sets.map { set ->
                if (set.role != CardioRole.WORK) return@map set
                var time = ((set.end as? Measure.Time) ?: (set.aim as? Measure.Time))?.seconds
                var distance = ((set.end as? Measure.Distance) ?: (set.aim as? Measure.Distance))?.metres
                when (measure) {
                    ProgressMeasure.DISTANCE -> {
                        val opening = requireNotNull(distance)
                        val increased = opening + step(opening)
                        distance = increased
                        time = time?.let { Math.round(it.toDouble() * increased / opening).toInt() }
                    }
                    ProgressMeasure.TIME -> {
                        val opening = requireNotNull(time)
                        val increased = opening + step(opening)
                        time = increased
                        distance = distance?.let { Math.round(it.toDouble() * increased / opening).toInt() }
                    }
                    ProgressMeasure.PACE -> when (set.end) {
                        is Measure.Distance -> time = set.time() - step(set.time())
                        is Measure.Time -> distance = set.distance() + step(set.distance())
                    }
                }
                set.copy(
                    end = when (set.end) {
                        is Measure.Time -> Measure.Time(requireNotNull(time))
                        is Measure.Distance -> Measure.Distance(requireNotNull(distance))
                    },
                    aim = when (set.aim) {
                        is Measure.Time -> Measure.Time(requireNotNull(time))
                        is Measure.Distance -> Measure.Distance(requireNotNull(distance))
                        null -> null
                    },
                )
            })
        } catch (_: IllegalArgumentException) {
            block
        }
    }

    /** Only an exact run of the block with every WORK end and aim met is a full success. */
    fun fullSuccess(block: CardioBlock, results: List<CardioSetResult>): Boolean {
        if (results.size != block.sets.size || results.indices.any { results[it].spec != block.sets[it] }) return false
        val work = results.filter { it.spec.role == CardioRole.WORK }
        return work.isNotEmpty() && work.all { result ->
            val end = result.spec.end
            result.status == CardioSetStatus.FINISHED &&
                (!block.openEnded || end !is Measure.Time || result.elapsedSeconds >= end.seconds) &&
                when (val aim = result.spec.aim) {
                    is Measure.Distance -> result.metres?.let { it >= aim.metres } == true
                    is Measure.Time -> result.elapsedSeconds <= aim.seconds
                    null -> true
                }
        }
    }

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
            ProgressMeasure.DISTANCE -> CardioIncrease.Distance(after.distance() - before.distance())
            ProgressMeasure.TIME -> CardioIncrease.Time(after.time() - before.time())
            ProgressMeasure.PACE -> when (before.end) {
                is Measure.Distance -> CardioIncrease.Faster(before.time() - after.time())
                is Measure.Time -> CardioIncrease.Distance(after.distance() - before.distance())
            }
        }
        return CardioNextTime.Offer(offered, opening, applied, change)
    }

    private fun CardioSetSpec.distance(): Int =
        requireNotNull((end as? Measure.Distance) ?: (aim as? Measure.Distance)).metres

    private fun CardioSetSpec.time(): Int =
        requireNotNull((end as? Measure.Time) ?: (aim as? Measure.Time)).seconds
}
