package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.checkmark.CheckmarkReset
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fall-back companion to `CheckmarkResetTest`'s spring-forward case
 * (PLAN.md A6): US clocks fall back 02:00 EDT -> 01:00 EST, so the wall-clock
 * hour 01:00-02:00 happens twice on the transition day. [CheckmarkReset]
 * compares calendar dates, never epoch millis or wall-clock time, so the
 * repeated hour is a non-event: both passes through it are the same New York
 * calendar day.
 */
class CheckmarkResetDstTest {

    private val sets = listOf(LoggedSet(245.0, 5, SetKind.TOP, done = true))

    @Test
    fun `a DST fall-back's repeated hour never spuriously resets`() {
        // US fall-back 2026-11-01: 01:30 New York time occurs twice.
        val ny = ZoneId.of("America/New_York")
        val firstPass = CheckmarkReset.today(Clock.fixed(Instant.parse("2026-11-01T05:30:00Z"), ny)) // 01:30 EDT
        val secondPass = CheckmarkReset.today(Clock.fixed(Instant.parse("2026-11-01T06:30:00Z"), ny)) // 01:30 EST (repeat)

        assertEquals("2026-11-01", firstPass)
        assertEquals("2026-11-01", secondPass)
        assertTrue(CheckmarkReset.applyResetIfStale(sets, "2026-11-01", secondPass).any { it.done })
    }

    @Test
    fun `real midnight after the fall-back still resets normally`() {
        val ny = ZoneId.of("America/New_York")
        // 2026-11-02T04:59Z == 2026-11-01 23:59 EST (still Nov 1).
        val beforeMidnight = CheckmarkReset.today(Clock.fixed(Instant.parse("2026-11-02T04:59:00Z"), ny))
        // 2026-11-02T05:00Z == 2026-11-02 00:00 EST (new day).
        val afterMidnight = CheckmarkReset.today(Clock.fixed(Instant.parse("2026-11-02T05:00:00Z"), ny))

        assertEquals("2026-11-01", beforeMidnight)
        assertEquals("2026-11-02", afterMidnight)
        assertTrue(CheckmarkReset.applyResetIfStale(sets, "2026-11-01", beforeMidnight).any { it.done })
        assertTrue(CheckmarkReset.applyResetIfStale(sets, "2026-11-01", afterMidnight).none { it.done })
    }
}
