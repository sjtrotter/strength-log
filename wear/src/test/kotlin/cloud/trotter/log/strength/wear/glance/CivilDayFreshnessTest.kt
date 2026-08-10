package cloud.trotter.log.strength.wear.glance

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class CivilDayFreshnessTest {
    @Test fun `utc cadence ends at the next civil day`() {
        val now = Instant.parse("2026-08-10T18:15:00Z")
        assertEquals(Duration.ofHours(5).plusMinutes(45).toMillis(), CivilDayFreshness.millisUntilNextDay(now, ZoneOffset.UTC))
    }

    @Test fun `spring DST day uses its real 23 hour boundary`() {
        val zone = ZoneId.of("America/New_York")
        val start = Instant.parse("2026-03-08T05:00:00Z")
        assertEquals(Duration.ofHours(23).toMillis(), CivilDayFreshness.millisUntilNextDay(start, zone))
    }

    @Test fun `fall DST day uses its real 25 hour boundary`() {
        val zone = ZoneId.of("America/New_York")
        val start = Instant.parse("2026-11-01T04:00:00Z")
        assertEquals(Duration.ofHours(25).toMillis(), CivilDayFreshness.millisUntilNextDay(start, zone))
    }
}
