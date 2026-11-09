package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure

/** Cardio measure math (A16): which measures a block supports, the increase, and full success. Moved from
 *  CardioProgression, which delegates here until PR 2b deletes it. */
object CardioMeasures {
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
                        is Measure.Distance -> time = set.timeSeconds() - step(set.timeSeconds())
                        is Measure.Time -> distance = set.distanceMetres() + step(set.distanceMetres())
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

    /** Only an exact run of the block with every WORK set finished and every aim met at its reading is a full success. */
    fun fullSuccess(block: CardioBlock, results: List<CardioSetResult>): Boolean {
        if (results.size != block.sets.size || results.indices.any { results[it].spec != block.sets[it] }) return false
        val work = results.filter { it.spec.role == CardioRole.WORK }
        return work.isNotEmpty() && work.all { result ->
            val end = result.spec.end
            (FinishedRule.finished(result) ?: (result.status == CardioSetStatus.FINISHED &&
                (!block.openEnded || end !is Measure.Time || result.elapsedSeconds >= end.seconds))) &&
                when (val aim = result.spec.aim) {
                    is Measure.Distance -> FinishedRule.reading(result)?.let { it >= aim.metres } == true
                    is Measure.Time -> FinishedRule.reading(result)?.let { it <= aim.seconds } == true
                    null -> true
                }
        }
    }
}

internal fun CardioSetSpec.distanceMetres(): Int =
    requireNotNull((end as? Measure.Distance) ?: (aim as? Measure.Distance)).metres

internal fun CardioSetSpec.timeSeconds(): Int =
    requireNotNull((end as? Measure.Time) ?: (aim as? Measure.Time)).seconds
