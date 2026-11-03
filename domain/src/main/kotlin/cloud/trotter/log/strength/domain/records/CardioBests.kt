package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure

data class CardioRecordKey(val activity: CardioActivity, val end: Measure)

data class CardioRecord(
    val key: CardioRecordKey,
    val best: CardioSetResult,
    val previous: CardioSetResult,
    val kind: PrKind,
)

object CardioBests {
    fun counts(result: CardioSetResult): Boolean =
        result.spec.role == CardioRole.WORK && result.status == CardioSetStatus.FINISHED &&
            (result.spec.end is Measure.Distance || result.metres != null)

    /** Positive means [a] is better; both results must have the same planned end measure. */
    fun compare(a: CardioSetResult, b: CardioSetResult): Int {
        require(a.spec.end == b.spec.end) { "Cannot compare different planned measures" }
        return when (a.spec.end) {
            is Measure.Distance -> b.elapsedSeconds.compareTo(a.elapsedSeconds)
            is Measure.Time -> requireNotNull(a.metres).compareTo(requireNotNull(b.metres))
        }
    }

    fun bests(activity: CardioActivity, results: List<CardioSetResult>): Map<CardioRecordKey, CardioSetResult> {
        val bests = mutableMapOf<CardioRecordKey, CardioSetResult>()
        for (result in results) {
            if (!counts(result)) continue
            val key = CardioRecordKey(activity, result.spec.end)
            val standing = bests[key]
            if (standing == null || compare(result, standing) > 0) bests[key] = result
        }
        return bests
    }

    fun records(
        activity: CardioActivity,
        results: List<CardioSetResult>,
        standing: Map<CardioRecordKey, CardioSetResult>,
    ): List<CardioRecord> = bests(activity, results).mapNotNull { (key, best) ->
        val previous = standing[key] ?: return@mapNotNull null
        if (!counts(previous) || previous.spec.end != key.end || compare(best, previous) <= 0) {
            return@mapNotNull null
        }
        val kind = when (key.end) {
            is Measure.Distance -> PrKind.FASTER
            is Measure.Time -> PrKind.FARTHER
        }
        CardioRecord(key, best, previous, kind)
    }
}
