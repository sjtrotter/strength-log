package io.github.sjtrotter.strengthlog.data.checkmark

import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import java.time.Clock
import java.time.LocalDate

/**
 * The daily checkmark-reset rule (spec §7, PLAN.md A6), kept as pure Kotlin so it
 * is testable on the JVM without Room.
 *
 * A set's `done` flag is only valid for the calendar date it was recorded on. On
 * read, if a log's stored date isn't "today" the checks surface as cleared while
 * weights and reps persist — so every new day starts fully unchecked.
 *
 * "Today" is the *device-local* calendar date. Comparison is on the date string,
 * never on epoch millis, which is why a daylight-saving transition can never
 * spuriously reset checks: a DST jump moves the clock, not the calendar day.
 */
object CheckmarkReset {

    /** Device-local today as a `yyyy-MM-dd` string. */
    fun today(clock: Clock = Clock.systemDefaultZone()): String =
        LocalDate.now(clock).toString()

    /**
     * Returns [sets] unchanged when [checkDate] is [today]; otherwise returns them
     * with every `done` flag cleared. Weights, reps and set kinds are untouched.
     */
    fun applyResetIfStale(sets: List<LoggedSet>, checkDate: String, today: String): List<LoggedSet> {
        if (checkDate == today) return sets
        return sets.map { if (it.done) it.copy(done = false) else it }
    }
}
