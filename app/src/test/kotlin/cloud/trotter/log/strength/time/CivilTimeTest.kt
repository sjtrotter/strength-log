package cloud.trotter.log.strength.time

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure half of the civil-time source (#176): one reading is internally
 * consistent, and it knows how long it stays valid.
 */
class CivilTimeTest {

    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun aReadingsDateIsTheOneItsInstantAndZoneImply() {
        val instant = Instant.parse("2026-08-01T02:30:00Z")

        assertEquals(LocalDate.of(2026, 8, 1), CivilTime.now(instant, ZoneOffset.UTC).date)
        // The same moment, still the previous day in New York.
        assertEquals(LocalDate.of(2026, 7, 31), CivilTime.now(instant, newYork).date)
    }

    @Test
    fun theReadingWaitsExactlyUntilTheNextLocalMidnight() {
        val reading = CivilTime.now(Instant.parse("2026-07-31T21:15:00Z"), ZoneOffset.UTC)

        assertEquals(Duration.ofHours(2).plusMinutes(45).toMillis(), reading.millisUntilNextDay())
    }

    /** The wait is local, not 24h arithmetic: New York is four hours behind UTC
     *  in July, so the same instant has a very different day left in it. */
    @Test
    fun theWaitIsMeasuredInTheReadingsOwnZone() {
        val instant = Instant.parse("2026-07-31T21:15:00Z") // 17:15 in New York

        assertEquals(Duration.ofHours(6).plusMinutes(45).toMillis(), CivilTime.now(instant, newYork).millisUntilNextDay())
    }

    /** A spring-forward day is 23 hours long, and its "midnight" is real —
     *  the gap is at 02:00, not at 00:00. */
    @Test
    fun aSpringForwardDayIsShorterButStillExactlyOneDay() {
        // 2026-03-07 in New York, the day before the US spring forward.
        val beforeTheJump = CivilTime.now(Instant.parse("2026-03-07T05:00:00Z"), newYork)
        assertEquals(Duration.ofHours(24).toMillis(), beforeTheJump.millisUntilNextDay())

        // The short day itself: midnight to midnight is 23 hours.
        val shortDay = CivilTime.now(Instant.parse("2026-03-08T05:00:00Z"), newYork)
        assertEquals(LocalDate.of(2026, 3, 8), shortDay.date)
        assertEquals(Duration.ofHours(23).toMillis(), shortDay.millisUntilNextDay())
    }
}
