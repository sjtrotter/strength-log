package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioPresentationTest {
    @Test fun `logged cardio uses the supplied civil day across midnight`() {
        val completedAt = Instant.parse("2026-11-19T23:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-11-20T01:00:00Z").toEpochMilli()
        val session = CardioSessionEntity(
            dayId = "A", planId = 1L, mode = "OUTDOOR_RUN", hard = false, label = "Run",
            startedAt = completedAt - 600_000L, completedAt = completedAt, seconds = 600, stepsCompleted = 1,
        )
        val sessions = listOf(session)
        assertEquals(session, sessions.loggedToday(1L, "A", now, ZoneId.of("America/Los_Angeles")))
        assertNull(sessions.loggedToday(1L, "A", now, ZoneOffset.UTC))
    }
}
