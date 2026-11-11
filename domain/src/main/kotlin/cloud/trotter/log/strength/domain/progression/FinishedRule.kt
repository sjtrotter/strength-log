package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure

/** The one "counts as finished" rule for records and progression (#526): a set is finished when its planned end was
 *  reached. An at-end field on the row marks a reached end (see [CardioSetResult.secondsAtEnd]); the totals keep the
 *  overrun. Records and comparisons read the reading at the end, never the totals, so there is no grace window. */
object FinishedRule {
    /** [result] with the readings at its planned end that its own totals prove, and no others (#526). No executor in
     *  this build reads distance while a set runs, so a FINISHED distance end was reached at the tap (its elapsed
     *  seconds and the planned metres); a FINISHED time end run exactly reads its seconds and [CardioSetResult.metres];
     *  a FINISHED time end run past its seconds (the phone's steady run) reads its seconds and no distance, since
     *  nothing read the distance then. A time end stopped short and every SHORTENED or SKIPPED set read nothing. */
    fun atEnd(result: CardioSetResult): CardioSetResult {
        val none = result.copy(metresAtEnd = null, secondsAtEnd = null)
        if (result.status != CardioSetStatus.FINISHED) return none
        return when (val end = result.spec.end) {
            is Measure.Distance -> none.copy(metresAtEnd = end.metres, secondsAtEnd = result.elapsedSeconds)
            is Measure.Time -> when {
                result.elapsedSeconds == end.seconds -> none.copy(metresAtEnd = result.metres, secondsAtEnd = end.seconds)
                result.elapsedSeconds > end.seconds -> none.copy(secondsAtEnd = end.seconds)
                else -> none
            }
        }
    }

    /** Readings matching [atEnd] of [previous] are derived; supplied readings survive edits to the totals. */
    fun afterEdit(previous: CardioSetResult, edited: CardioSetResult): CardioSetResult =
        if (previous == atEnd(previous)) atEnd(edited)
        else edited.copy(metresAtEnd = previous.metresAtEnd, secondsAtEnd = previous.secondsAtEnd)

    /** True when the row reached its planned end and finished; false when it carries an at-end field but did not
     *  finish; null for a row with no at-end field (before v25), whose reader keeps its own rule. */
    fun finished(result: CardioSetResult): Boolean? =
        if (result.metresAtEnd == null && result.secondsAtEnd == null) null
        else result.status == CardioSetStatus.FINISHED

    /** What a record or an aim compares: metres for a time end, seconds for a distance end. From the at-end reading on
     *  a row that has one (null when that reading is missing), from the totals on an older row. */
    fun reading(result: CardioSetResult): Int? = if (finished(result) != null) {
        when (result.spec.end) {
            is Measure.Time -> result.metresAtEnd
            is Measure.Distance -> result.secondsAtEnd
        }
    } else {
        when (result.spec.end) {
            is Measure.Time -> result.metres
            is Measure.Distance -> result.elapsedSeconds
        }
    }
}
