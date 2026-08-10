package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class CardioJournalTest {
    @Test fun `strength and cardio interleave newest completion first`() {
        val strength = SessionListItem(1, "", "A", 0, "Strength", 1, null, false, completedAt = 100)
        val cardio = SessionListItem(2, "", "A", 0, "Cardio", 0, null, false, completedAt = 200, cardioId = 2)
        assertEquals(listOf("Cardio", "Strength"), LogScreenBuilder.interleave(listOf(strength), listOf(cardio)).map { it.dayTitle })
    }

    @Test fun `equal completion times order deterministically, strength first`() {
        val strength = SessionListItem(7, "", "A", 0, "Strength", 1, null, false, completedAt = 100)
        val cardio = SessionListItem(7, "", "A", 0, "Cardio", 0, null, false, completedAt = 100, cardioId = 7)
        val merged = LogScreenBuilder.interleave(listOf(strength), listOf(cardio))
        assertEquals(listOf("Strength", "Cardio"), merged.map { it.dayTitle })
    }

        @Test fun `unknown mode keeps a readable display word`() {
        val item = LogScreenBuilder.cardioItem(cardio(mode = "FUTURE_MODE"))
        assertEquals("Easy Zone 2", item.dayTitle)
        assertEquals("FUTURE MODE", item.cardioSummary)
        assertEquals("1:00", item.cardioDuration)
    }

    @Test fun `a day holding both types earns one dot, not two`() {
        val completed = LocalDate.of(2026, 8, 10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val strengthSession = strengthSummaryAt(completed)
        val month = JournalBuilder.calendar(listOf(strengthSession), 0, LocalDate.of(2026, 8, 10), ZoneOffset.UTC, listOf(cardio(completedAt = completed)))
        assertEquals(1, month!!.days.count { it.dayOfMonth == 10 && it.dayLetter != null })
    }

        @Test fun `calendar includes a cardio-only day dot`() {
        val completed = LocalDate.of(2026, 8, 10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val month = JournalBuilder.calendar(emptyList(), 0, LocalDate.of(2026, 8, 10), ZoneOffset.UTC, listOf(cardio(completedAt = completed)))
        val day = month!!.days.single { it.dayOfMonth == 10 }
        assertEquals("A", day.dayLetter)
        assertEquals(null, day.sessionId)
    }

    private fun strengthSummaryAt(completedAt: Long) = SessionSummaryRow(
        session = WorkoutSessionEntity(
            id = 9, dayId = "A", dayTitle = "Day A", startedAt = null,
            completedAt = completedAt, bodyweightLb = 200,
        ),
        setCount = 8,
    )

    private fun cardio(mode: String = "OUTDOOR_RUN", completedAt: Long = 61_000) = CardioSessionEntity(
        id = 7, dayId = "A", mode = mode, hard = false, label = "Easy Zone 2",
        startedAt = completedAt - 60_000, completedAt = completedAt, seconds = 60, stepsCompleted = 0,
    )
}
