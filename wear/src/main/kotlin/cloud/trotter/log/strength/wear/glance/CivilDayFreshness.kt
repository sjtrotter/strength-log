package cloud.trotter.log.strength.wear.glance

import java.time.Instant
import java.time.ZoneId

/** Reads the active zone per render and finds the next real local-day boundary (DST included). */
internal object CivilDayFreshness {
    fun millisUntilNextDay(now: Instant, zone: ZoneId): Long {
        val tomorrow = now.atZone(zone).toLocalDate().plusDays(1)
        val boundary = tomorrow.atStartOfDay(zone).toInstant()
        return (boundary.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1L)
    }
}
