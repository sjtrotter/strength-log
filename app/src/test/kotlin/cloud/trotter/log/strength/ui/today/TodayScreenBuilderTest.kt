package cloud.trotter.log.strength.ui.today

import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.time.CivilTime
import cloud.trotter.log.strength.ui.log.JournalBuilder
import cloud.trotter.log.strength.ui.text.TodayActionKind
import cloud.trotter.log.strength.ui.text.TodayCoachKind
import cloud.trotter.log.strength.ui.text.UiText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayScreenBuilderTest {
    private val zone = ZoneId.of("UTC")
    private val nowInstant = Instant.parse("2026-08-24T12:00:00Z")
    private val now = CivilTime(nowInstant, zone, LocalDate.of(2026, 8, 24))

    @Test
    fun standalone_cardio_line_text() {
        assertEquals("Log cardio + core, 25 min", TodayScreenBuilder.standaloneCardioLine())
    }

    @Test
    fun lifeLine_prefers_the_main_lifts_consecutive_goal_sessions() {
        val sessions = listOf(lifeSummary(3, 2026, 8, 24), lifeSummary(2, 2026, 8, 20))
        val topSets = listOf(
            topSet(1, "bb_back_squat", 225.0),
            topSet(2, "bb_back_squat", 235.0),
            topSet(3, "bb_back_squat", 240.0),
        )

        assertEquals(
            UiText.TodayGoalLife("Squat", 2),
            TodayScreenBuilder.lifeLine(
                listOf(JournalBuilder.MainLift("bb_back_squat", "Squat", 0, 235.0)),
                sessions,
                topSets,
                4,
                now,
            ),
        )
    }

    @Test
    fun lifeLine_uses_this_calendar_week_when_no_main_is_at_goal() {
        val saturday = CivilTime(
            Instant.parse("2026-08-22T17:00:00Z"),
            zone,
            LocalDate.of(2026, 8, 22),
        )
        assertEquals(
            UiText.TodayWeekLife(sessions = 2, rotationDays = 4, daysAgo = 2),
            TodayScreenBuilder.lifeLine(
                emptyList(),
                listOf(lifeSummary(2, 2026, 8, 20), lifeSummary(1, 2026, 8, 18)),
                emptyList(),
                4,
                saturday,
            ),
        )
    }

    @Test
    fun lifeLine_uses_last_session_and_month_count_outside_this_week() {
        assertEquals(
            UiText.TodayHistoryLife(daysAgo = 8, monthSessions = 3),
            TodayScreenBuilder.lifeLine(
                emptyList(),
                listOf(
                    lifeSummary(3, 2026, 8, 16),
                    lifeSummary(2, 2026, 8, 10),
                    lifeSummary(1, 2026, 8, 2),
                ),
                emptyList(),
                4,
                now,
            ),
        )
    }

    @Test
    fun lifeLine_is_absent_without_history() {
        assertNull(TodayScreenBuilder.lifeLine(emptyList(), emptyList(), emptyList(), 4, now))
    }

    @Test
    fun coachLine_is_absent_without_history() {
        assertNull(TodayScreenBuilder.coachLine(null, nowInstant, zone))
    }

    @Test
    fun coachLine_says_today_at_zero_days() {
        assertEquals(
            UiText.TodayCoach(TodayCoachKind.TODAY),
            TodayScreenBuilder.coachLine(daysAgo(0), nowInstant, zone),
        )
    }

    @Test
    fun coachLine_says_yesterday_at_one_day() {
        assertEquals(
            UiText.TodayCoach(TodayCoachKind.YESTERDAY),
            TodayScreenBuilder.coachLine(daysAgo(1), nowInstant, zone),
        )
    }

    @Test
    fun coachLine_uses_days_at_five_days() {
        assertEquals(
            UiText.TodayCoach(TodayCoachKind.DAYS, "19 Aug", 5),
            TodayScreenBuilder.coachLine(daysAgo(5), nowInstant, zone),
        )
    }

    @Test
    fun coachLine_uses_days_at_thirteen_days() {
        assertEquals(
            UiText.TodayCoach(TodayCoachKind.DAYS, "11 Aug", 13),
            TodayScreenBuilder.coachLine(daysAgo(13), nowInstant, zone),
        )
    }

    @Test
    fun coachLine_uses_weeks_at_fourteen_days() {
        assertEquals(
            UiText.TodayCoach(TodayCoachKind.WEEKS, "10 Aug", 2),
            TodayScreenBuilder.coachLine(daysAgo(14), nowInstant, zone),
        )
    }

    @Test
    fun coachLine_rounds_sixty_nine_days_to_ten_weeks() {
        assertEquals(
            UiText.TodayCoach(TodayCoachKind.WEEKS, "16 Jun", 10),
            TodayScreenBuilder.coachLine(daysAgo(69), nowInstant, zone),
        )
    }

    @Test
    fun progressLine_reports_logged_sets() {
        assertEquals(UiText.TodayProgress(4, 21), TodayScreenBuilder.progressLine(4, 21))
    }

    @Test
    fun actionLabel_starts_a_fresh_day() {
        assertEquals(TodayActionKind.START, TodayScreenBuilder.actionLabel("B", 0, 18).kind)
    }

    @Test
    fun actionLabel_continues_a_partial_day() {
        assertEquals(TodayActionKind.CONTINUE, TodayScreenBuilder.actionLabel("B", 4, 18).kind)
    }

    @Test
    fun actionLabel_finishes_a_complete_day() {
        assertEquals(TodayActionKind.FINISH, TodayScreenBuilder.actionLabel("B", 18, 18).kind)
    }

    @Test
    fun actionLabel_preserves_the_semantic_day_id_lowercase() {
        assertEquals("b", TodayScreenBuilder.actionLabel("b", 0, 18).dayId)
    }

    private fun daysAgo(days: Long): Instant = nowInstant.minus(days, ChronoUnit.DAYS)

    private fun topSet(sessionId: Long, exerciseId: String, weightLb: Double) =
        TopSetRow(sessionId, 0, exerciseId, weightLb)

    private fun lifeSummary(sessionId: Long, year: Int, month: Int, day: Int): SessionSummaryRow {
        val completedAt = LocalDate.of(year, month, day).atTime(12, 0).atZone(zone)
            .toInstant().toEpochMilli()
        return SessionSummaryRow(
            WorkoutSessionEntity(sessionId, "B", "Lower", null, completedAt, 235),
            10,
        )
    }
}
