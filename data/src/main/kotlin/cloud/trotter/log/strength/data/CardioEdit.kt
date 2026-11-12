package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.progression.FinishedRule

/** Shared validation for a single-set History edit and its save. */
object CardioEdit {
    fun apply(previous: CardioSetResult, seconds: Int, metres: Int?, readingAtEnd: Int?): CardioSetResult? {
        if (seconds !in 1..ManualCardio.MAX_SECONDS ||
            (metres != null && metres !in 1..ManualCardio.MAX_METRES)) return null
        val edited = previous.copy(
            spec = if ((previous.spec.end as? Measure.Time)?.seconds == previous.elapsedSeconds) {
                previous.spec.copy(end = Measure.Time(seconds))
            } else previous.spec,
            elapsedSeconds = seconds,
            metres = metres,
            distanceSource = if (metres == previous.metres) previous.distanceSource else metres?.let { DistanceSource.MANUAL },
        )
        if (readingAtEnd != null) return FinishedRule.typed(edited, readingAtEnd)
        val result = FinishedRule.afterEdit(previous, edited)
        return if (result.distanceSource == DistanceSource.GPS && previous.readingSource == null) {
            result.copy(metresAtEnd = null)
        } else result
    }
}
