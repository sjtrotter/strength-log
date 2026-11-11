package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.progression.FinishedRule
import cloud.trotter.log.strength.domain.units.DistanceUnit

/** The after-run entry's optional reading at a planned end the set passed (owner decision 2026-11-10). */
internal object CardioReadingEntry {
    enum class Check { VALID, NOT_POSITIVE, OVER_TOTAL }

    /** The set at [position] with the typed [total], when it awaits a reading; null hides the field. */
    fun awaiting(run: CardioRun, position: Int, total: Int?): CardioSetResult? {
        if (total == null) return null
        return run.resultSets.getOrNull(position)?.copy(metres = total, distanceSource = DistanceSource.MANUAL)
            ?.takeIf(FinishedRule::awaitsReading)
    }

    /** [text] as metres (time end, typed in [unit]) or seconds (distance end, `m:ss`); null when it is not a value
     *  above 0. A distance past the entry's range reads as [Int.MAX_VALUE], which is over any total. */
    fun value(end: Measure, text: String, unit: DistanceUnit): Int? = when (end) {
        is Measure.Time -> {
            val typed = text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            when {
                typed == null -> null
                typed > unit.fromMetres(ManualCardio.MAX_METRES) -> Int.MAX_VALUE
                else -> unit.toMetres(typed)
            }
        }
        is Measure.Distance -> parseSheetDuration(text)?.toInt()?.takeIf { it > 0 }
    }

    fun check(awaiting: CardioSetResult, text: String, unit: DistanceUnit): Check {
        val value = value(awaiting.spec.end, text, unit) ?: return Check.NOT_POSITIVE
        return if (FinishedRule.withReading(awaiting, value) != null) Check.VALID else Check.OVER_TOTAL
    }
}
