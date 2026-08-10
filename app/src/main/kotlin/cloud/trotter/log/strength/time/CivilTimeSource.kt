package cloud.trotter.log.strength.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * One reading of the device's civil day: the [instant] it was taken at, the
 * [zone] that was in effect at that moment, and the [date] those two imply.
 *
 * The three travel together because they have to agree. A screen that takes
 * "now" from one place and the zone from another can render a date that belongs
 * to neither — which is exactly what the singleton `Clock.systemDefaultZone()`
 * did (#176): `Clock.SystemClock` captures `ZoneId.systemDefault()` once, at
 * construction, and never re-reads it, so after a timezone change every week and
 * month boundary kept being computed in the old zone.
 */
data class CivilTime(val instant: Instant, val zone: ZoneId, val date: LocalDate) {

    /**
     * Milliseconds from [instant] to the start of the next local day — the wait
     * before this reading goes stale. Floored at 1 so that a reading whose three
     * parts don't agree can't spin a caller that loops on it.
     *
     * `atStartOfDay(zone)` rather than midnight arithmetic: on a DST spring
     * forward where 00:00 doesn't exist locally, it resolves to the first valid
     * instant of the day instead of a time that never happens.
     */
    fun millisUntilNextDay(): Long {
        val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant()
        return (nextDay.toEpochMilli() - instant.toEpochMilli()).coerceAtLeast(1L)
    }

    companion object {
        /** Reads the system zone on every call — never hold onto a [ZoneId]. */
        fun now(instant: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): CivilTime =
            CivilTime(instant, zone, instant.atZone(zone).toLocalDate())
    }
}

/**
 * Where a screen gets "what day is it here, right now" (#176).
 *
 * [civilTime] is the whole point: an injected `Clock` answers the question once
 * and can only be re-asked when some *other* flow happens to emit, so a screen
 * parked across midnight keeps yesterday's date. A flow re-asks it on its own
 * schedule.
 */
interface CivilTimeSource {

    /**
     * The current civil day: one value on collection, then a new one every time
     * the local date or the zone actually changes. Nothing is emitted for a day
     * that is still the same day, so a consumer can combine this into its state
     * pipeline without paying for a recomputation per tick.
     */
    val civilTime: Flow<CivilTime>

    /**
     * Re-reads the wall clock now — for resume, where the app may have been away
     * long enough for the day to have turned but not long enough for collection
     * to have restarted.
     */
    fun refresh()
}
