package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.checkmark.CheckmarkReset
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The daily checkmark-reset rule (spec §7 "checkmark semantics", PLAN.md A6). */
class CheckmarkResetTest {

    private val sets = listOf(
        LoggedSet(235.0, 5, SetKind.TOP, done = true),
        LoggedSet(175.0, 8, SetKind.BACKOFF, done = true),
        LoggedSet(190.0, 5, SetKind.RAMP, done = false),
    )

    @Test
    fun `checks are kept when the stored date is today`() {
        val out = CheckmarkReset.applyResetIfStale(sets, checkDate = "2026-07-06", today = "2026-07-06")
        assertEquals(sets, out)
    }

    @Test
    fun `checks are cleared when the stored date is stale, weights and reps persist`() {
        val out = CheckmarkReset.applyResetIfStale(sets, checkDate = "2026-07-05", today = "2026-07-06")
        assertTrue(out.none { it.done }, "all done flags cleared")
        // Everything else is untouched.
        assertEquals(sets.map { it.copy(done = false) }, out)
    }

    @Test
    fun `an empty check date (explicitly cleared) reads as unchecked`() {
        val out = CheckmarkReset.applyResetIfStale(sets, checkDate = "", today = "2026-07-06")
        assertTrue(out.none { it.done })
    }

    // --- device-local "today" and the midnight / DST edges (PLAN.md A6) -------

    @Test
    fun `today is the device-local calendar date`() {
        // 2026-07-06 03:00 UTC is still 2026-07-05 in New York (UTC-4).
        val instant = Instant.parse("2026-07-06T03:00:00Z")
        assertEquals("2026-07-05", CheckmarkReset.today(Clock.fixed(instant, ZoneId.of("America/New_York"))))
        assertEquals("2026-07-06", CheckmarkReset.today(Clock.fixed(instant, ZoneId.of("UTC"))))
    }

    @Test
    fun `crossing local midnight resets, one minute earlier does not`() {
        val ny = ZoneId.of("America/New_York")
        // 2026-07-07T03:59Z == 2026-07-06 23:59 in NY (still the checked day).
        val beforeMidnight = CheckmarkReset.today(Clock.fixed(Instant.parse("2026-07-07T03:59:00Z"), ny))
        // 2026-07-07T04:00Z == 2026-07-07 00:00 in NY (new day).
        val afterMidnight = CheckmarkReset.today(Clock.fixed(Instant.parse("2026-07-07T04:00:00Z"), ny))

        assertEquals("2026-07-06", beforeMidnight)
        assertEquals("2026-07-07", afterMidnight)

        assertTrue(CheckmarkReset.applyResetIfStale(sets, "2026-07-06", beforeMidnight).any { it.done })
        assertFalse(CheckmarkReset.applyResetIfStale(sets, "2026-07-06", afterMidnight).any { it.done })
    }

    @Test
    fun `a DST spring-forward within a day never spuriously resets`() {
        // US spring-forward: 2026-03-08, clocks jump 02:00 -> 03:00 EST->EDT.
        // Both instants below are the SAME New York calendar day (2026-03-08), so
        // a set checked before the jump is still checked after it. The rule
        // compares calendar dates, never epoch millis, so the lost hour is a
        // non-event.
        val ny = ZoneId.of("America/New_York")
        val beforeJump = CheckmarkReset.today(Clock.fixed(Instant.parse("2026-03-08T06:30:00Z"), ny)) // 01:30 EST
        val afterJump = CheckmarkReset.today(Clock.fixed(Instant.parse("2026-03-08T07:30:00Z"), ny))  // 03:30 EDT

        assertEquals("2026-03-08", beforeJump)
        assertEquals("2026-03-08", afterJump)
        assertTrue(CheckmarkReset.applyResetIfStale(sets, "2026-03-08", afterJump).any { it.done })
    }
}
