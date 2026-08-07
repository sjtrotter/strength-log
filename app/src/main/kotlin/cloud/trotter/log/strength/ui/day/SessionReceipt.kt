package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.glance.GlanceLines
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.units.WeightUnit

/**
 * The session's receipt (#126): what the lifter just did, read back to them
 * once DONE has already committed it. It sits between the advance and landing
 * on Today — the workout used to end with nothing but the rotation quietly
 * moving on.
 *
 * Everything here is a read of the session row that [cloud.trotter.log.strength
 * .data.TrackerRepository.advanceDay] has already written, so the receipt is
 * post-commit UI over persisted data: dismissing it, backing out of it, or
 * losing the process to a kill all leave exactly the same saved session behind.
 * Like [CascadeCeremony] it is therefore a moment and not data — a plain
 * ViewModel field, so it survives rotation and dies with the process rather
 * than reappearing over a workout the lifter already walked away from.
 *
 * [sessionId] is the receipt's only live handle: SHARE hands it to the same
 * [cloud.trotter.log.strength.ui.log.share.ShareCardService] the Log screen
 * uses, so there is still exactly one place a share Intent gets built
 * (session-share brief §4).
 */
data class SessionReceipt(
    val sessionId: Long,
    /** The *completed* day's index — the receipt wears the accent of the day it
     *  reports on, not of the one the rotation has already moved to. */
    val dayIndex: Int,
    /** "DAY A COMPLETE". */
    val headline: String,
    /** The completed day's title ("LOWER"); blank when the day has none. */
    val dayTitle: String,
    /** Rounds actually ticked — the count the header was showing a moment ago
     *  (see [Slot.isRound]), not a second way of counting the same day. */
    val setCount: Int,
    /** The session's heaviest completed set; null when nothing weighted was
     *  completed (see [SessionReceiptBuilder.from]). */
    val strongest: ReceiptLift?,
    /** "DAY B · UPPER" — where the rotation now stands; null at the end of a
     *  program with no successor. */
    val nextDayLine: String?,
)

/** The strongest-set row: the lift's name and its set, already formatted. */
data class ReceiptLift(val name: String, val value: String)

object SessionReceiptBuilder {

    /**
     * Builds the receipt for a session [advanceDay][cloud.trotter.log.strength
     * .data.TrackerRepository.advanceDay] has just written. [sessionSets] is the
     * same list the cascade check reads — one fetch serves both, so the two
     * post-DONE surfaces can never disagree about what the session contained.
     *
     * The set count is *rounds* ([Slot.isRound]) — the number the day header was
     * showing a second before this surface replaced it. Counting a superset
     * partner's row as well would double a superset day's total against the
     * header, Today and the watch, which all count the round the single tick
     * covered.
     *
     * "Strongest" deliberately does not narrow that way: it is the heaviest
     * *loaded* set of the session, partner tracks included, because a partner
     * set is still weight the lifter moved. Sets are classified by their own
     * logged values ([SetFormatter.trackingOfValues], the legacy-history rule
     * the whole app shares) and only weighted ones are ranked, heaviest first
     * and reps breaking the tie. A day of holds and bodyweight reps has no
     * strongest set and simply shows no such line — a plank is not a number to
     * beat, and inventing one would be the fake trophy this receipt exists to
     * avoid.
     *
     * [nextDayId] is null only in the guard case where the completed day is no
     * longer in the program by the time the advance lands (a wizard re-run
     * racing a DONE); the rotation itself always wraps and always has a next.
     */
    fun from(
        sessionId: Long,
        dayId: String,
        dayIndex: Int,
        dayTitle: String,
        sessionSets: List<SessionSetEntity>,
        nextDayId: String?,
        nextDayTitle: String,
        unit: WeightUnit,
    ): SessionReceipt {
        val done = sessionSets.filter { it.done }
        val strongest = done
            .filter { SetFormatter.trackingOfValues(it.weightLb, it.reps, it.seconds) == TrackingType.WEIGHTED }
            .filter { it.weightLb > 0.0 }
            .maxWithOrNull(compareBy({ it.weightLb }, { it.reps }))
        return SessionReceipt(
            sessionId = sessionId,
            dayIndex = dayIndex,
            headline = "DAY ${dayId.uppercase()} COMPLETE",
            dayTitle = dayTitle,
            setCount = done.count { Slot.isRound(it.slot) },
            strongest = strongest?.let {
                ReceiptLift(it.exerciseName, SetFormatter.summaryOfValues(it.weightLb, it.reps, it.seconds, unit))
            },
            nextDayLine = nextDayId?.let { GlanceLines.dayLine(it, nextDayTitle) },
        )
    }
}
