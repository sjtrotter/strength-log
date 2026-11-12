package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.GpsSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ReadingSource

/** The one "counts as finished" rule for records and progression (#526): a set is finished when its planned end was
 *  reached. An at-end field on the row marks a reached end (see [CardioSetResult.secondsAtEnd]); the totals keep the
 *  overrun. Records and comparisons read the reading at the end, never the totals, so there is no grace window. */
object FinishedRule {
    /** [result] with the readings at its planned end that its own totals prove, and no others (#526). This rule
     *  derives readings from the totals; a reading the phone's GPS measured arrives through [measured] instead.
     *  A FINISHED distance end reads its planned metres and elapsed
     *  seconds unless its total passed the end, when no time is known there. A FINISHED time end run exactly reads
     *  its seconds and [CardioSetResult.metres];
     *  a FINISHED time end run past its seconds (the phone's steady run) reads its seconds and no distance, since
     *  nothing read the distance then. A time end stopped short and every SHORTENED or SKIPPED set read nothing. */
    fun atEnd(result: CardioSetResult): CardioSetResult {
        val none = result.copy(metresAtEnd = null, secondsAtEnd = null, readingSource = null)
        if (result.status != CardioSetStatus.FINISHED) return none
        return when (val end = result.spec.end) {
            is Measure.Distance -> none.copy(metresAtEnd = end.metres,
                secondsAtEnd = result.elapsedSeconds.takeIf { (result.metres ?: 0) <= end.metres })
            is Measure.Time -> when {
                result.elapsedSeconds == end.seconds -> none.copy(metresAtEnd = result.metres, secondsAtEnd = end.seconds)
                result.elapsedSeconds > end.seconds -> none.copy(secondsAtEnd = end.seconds)
                else -> none
            }
        }
    }

    /** Readings with a source survive edits to the totals; derived readings are recomputed. */
    fun afterEdit(previous: CardioSetResult, edited: CardioSetResult): CardioSetResult =
        if (previous.readingSource != null) edited.copy(metresAtEnd = previous.metresAtEnd,
            secondsAtEnd = previous.secondsAtEnd, readingSource = previous.readingSource)
        else atEnd(edited)

    /** What the phone's GPS measured over [result] (owner decision 2026-11-11): the total of a
     *  set the track covered when nothing was typed, and the reading at its planned end when two fresh fixes
     *  bracket that moment. A covered set past its end with no such reading keeps that reading unknown. */
    fun measured(result: CardioSetResult, gps: GpsSet?): CardioSetResult {
        if (gps == null || !gps.complete || result.metres != null || result.status == CardioSetStatus.SKIPPED) return result
        val total = result.copy(metres = gps.metres, distanceSource = DistanceSource.GPS)
        return when (val end = result.spec.end) {
            is Measure.Time -> if (result.status == CardioSetStatus.FINISHED && result.elapsedSeconds >= end.seconds) {
                total.copy(metresAtEnd = gps.reading, secondsAtEnd = end.seconds,
                    readingSource = gps.reading?.let { ReadingSource.GPS })
            } else total
            is Measure.Distance -> if (gpsPassed(end, gps)) {
                total.copy(status = CardioSetStatus.FINISHED, metresAtEnd = end.metres,
                    secondsAtEnd = gps.reading, readingSource = ReadingSource.GPS)
            } else total
        }
    }

    fun gpsPassed(end: Measure.Distance, gps: GpsSet): Boolean =
        gps.reading != null && gps.metres > end.metres + GpsSet.RESOLUTION_METRES

    /** True when [result] passed its planned end and has no reading there: a FINISHED time end stopped after its
     *  seconds with no [CardioSetResult.metresAtEnd], or a distance end whose total passed its metres with no
     *  [CardioSetResult.secondsAtEnd]. When GPS couldn't read that moment, only the user can supply it. */
    fun awaitsReading(result: CardioSetResult): Boolean = when (val end = result.spec.end) {
        is Measure.Time -> result.status == CardioSetStatus.FINISHED && result.elapsedSeconds > end.seconds &&
            result.metresAtEnd == null
        is Measure.Distance -> (result.metres ?: 0) > end.metres && result.secondsAtEnd == null
    }

    /** [result] with the reading the user supplied at its planned end (metres for a time end, seconds for a distance
     *  end, as [reading] reads them), or null when it awaits none or [reading] is not within 1 and its total. A
     *  distance end passed is FINISHED: its end was reached. The totals keep the overrun. */
    fun withReading(result: CardioSetResult, reading: Int): CardioSetResult? {
        if (!awaitsReading(result) || reading < 1) return null
        return when (val end = result.spec.end) {
            is Measure.Time -> result.copy(metresAtEnd = reading, secondsAtEnd = end.seconds, readingSource = ReadingSource.TYPED)
                .takeIf { reading <= (result.metres ?: 0) }
            is Measure.Distance -> result.copy(status = CardioSetStatus.FINISHED, metresAtEnd = end.metres,
                secondsAtEnd = reading, readingSource = ReadingSource.TYPED).takeIf { reading <= result.elapsedSeconds }
        }
    }

    /** A user-supplied reading at a reached end, including a replacement for an existing reading. */
    fun typed(result: CardioSetResult, reading: Int): CardioSetResult? = when (val end = result.spec.end) {
        is Measure.Time -> if (result.status == CardioSetStatus.FINISHED && result.elapsedSeconds >= end.seconds &&
            reading in 1..(result.metres ?: 0)) {
            result.copy(metresAtEnd = reading, secondsAtEnd = end.seconds, readingSource = ReadingSource.TYPED)
        } else null
        is Measure.Distance -> if ((result.metres ?: 0) >= end.metres && reading in 1..result.elapsedSeconds) {
            result.copy(status = CardioSetStatus.FINISHED, metresAtEnd = end.metres,
                secondsAtEnd = reading, readingSource = ReadingSource.TYPED)
        } else null
    }

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
