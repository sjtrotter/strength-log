package cloud.trotter.log.strength.ui.today

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayScreenBuilderTest {

    @Test fun standalone_cardio_line_only_exists_when_program_has_cardio_days() {
        assertNull(TodayScreenBuilder.standaloneCardioLine(false))
        assertEquals("CARDIO + CORE · 25 MIN · LOG", TodayScreenBuilder.standaloneCardioLine(true))
    }

    private val catalog = ExerciseCatalog.CODE_ONLY

    @Test
    fun overline_shows_next_for_a_fresh_day() {
        assertEquals("NEXT IN ROTATION", TodayScreenBuilder.overline(0, 18))
    }

    @Test
    fun overline_shows_in_progress_for_a_partial_day() {
        assertEquals("IN PROGRESS", TodayScreenBuilder.overline(4, 18))
    }

    @Test
    fun overline_shows_ready_for_a_complete_day() {
        assertEquals("READY TO FINISH", TodayScreenBuilder.overline(18, 18))
    }

    @Test
    fun overline_shows_ready_for_an_over_count() {
        assertEquals("READY TO FINISH", TodayScreenBuilder.overline(19, 18))
    }

    @Test
    fun overline_shows_next_for_an_empty_day() {
        assertEquals("NEXT IN ROTATION", TodayScreenBuilder.overline(0, 0))
    }

    @Test
    fun actionLabel_starts_a_fresh_day() {
        assertEquals(cloud.trotter.log.strength.ui.text.TodayActionKind.START, TodayScreenBuilder.actionLabel("B", 0, 18).kind)
    }

    @Test
    fun actionLabel_continues_a_partial_day() {
        assertEquals(cloud.trotter.log.strength.ui.text.TodayActionKind.CONTINUE, TodayScreenBuilder.actionLabel("B", 4, 18).kind)
    }

    @Test
    fun actionLabel_finishes_a_complete_day() {
        assertEquals(cloud.trotter.log.strength.ui.text.TodayActionKind.FINISH, TodayScreenBuilder.actionLabel("B", 18, 18).kind)
    }

    @Test
    fun actionLabel_preserves_the_semantic_day_id_lowercase() {
        assertEquals("b", TodayScreenBuilder.actionLabel("b", 0, 18).dayId)
    }

    @Test
    fun cardioIntentLine_doesNotRepeatTheEasyMode() {
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.TodayCardio(false, "Easy Zone 2"), TodayScreenBuilder.cardioIntentLine(hard = false, label = "Easy Zone 2"))
    }

    @Test
    fun cardioIntentLine_doesNotRepeatTheHardModeAndRecapitalizes() {
        assertEquals(
            cloud.trotter.log.strength.ui.text.UiText.TodayCardio(true, "Hard cardio — intervals"),
            TodayScreenBuilder.cardioIntentLine(hard = true, label = "Hard cardio — intervals"),
        )
    }

    @Test
    fun rotationMarks_preserve_indices_and_mark_the_next_day() {
        assertEquals(
            listOf(
                RotationMark("A", 0, false),
                RotationMark("B", 1, false),
                RotationMark("C", 2, true),
                RotationMark("D", 3, false),
            ),
            TodayScreenBuilder.rotationMarks(listOf("A", "B", "C", "D"), "C"),
        )
    }

    @Test
    fun topLiftDisplay_returns_null_for_empty_rows() {
        assertNull(TodayScreenBuilder.topLiftDisplay(emptyList(), catalog, WeightUnit.LB))
    }

    @Test
    fun topLiftDisplay_picks_the_heaviest_row() {
        val rows = listOf(topSet(1, "bb_back_squat", 225.0), topSet(1, "bb_bench", 245.0))
        assertEquals("Barbell Bench Press 245", TodayScreenBuilder.topLiftDisplay(rows, catalog, WeightUnit.LB))
    }

    @Test
    fun topLiftDisplay_falls_back_to_the_exercise_id() {
        assertEquals(
            "custom_unknown 100",
            TodayScreenBuilder.topLiftDisplay(listOf(topSet(1, "custom_unknown", 100.0)), catalog, WeightUnit.LB),
        )
    }

    @Test
    fun topLiftDisplay_converts_to_kg() {
        assertEquals(
            "Barbell Back Squat 45.36",
            TodayScreenBuilder.topLiftDisplay(listOf(topSet(1, "bb_back_squat", 100.0)), catalog, WeightUnit.KG),
        )
    }

    @Test
    fun lastSessionLine_returns_null_for_empty_sessions() {
        assertNull(TodayScreenBuilder.lastSessionLine(emptyList(), emptyList(), catalog, WeightUnit.LB))
    }

    @Test
    fun lastSessionLine_uses_the_newest_session_only() {
        val sessions = listOf(summary(2, 2026, 7, 30, 18), summary(1, 2026, 7, 20, 12))
        val topSets = listOf(topSet(2, "bb_back_squat", 245.0), topSet(1, "bb_bench", 300.0))

        assertEquals(
            "Jul 30, 2026 · 18 sets · Barbell Back Squat 245",
            TodayScreenBuilder.lastSessionLine(sessions, topSets, catalog, WeightUnit.LB),
        )
    }

    @Test
    fun lastSessionLine_omits_the_top_lift_when_the_session_has_none() {
        assertEquals(
            "Jul 30, 2026 · 18 sets",
            TodayScreenBuilder.lastSessionLine(
                listOf(summary(2, 2026, 7, 30, 18)),
                emptyList(),
                catalog,
                WeightUnit.LB,
            ),
        )
    }

    @Test
    fun lastSessionLine_ignores_top_sets_from_other_sessions() {
        assertEquals(
            "Jul 30, 2026 · 18 sets",
            TodayScreenBuilder.lastSessionLine(
                listOf(summary(2, 2026, 7, 30, 18)),
                listOf(topSet(1, "bb_back_squat", 245.0)),
                catalog,
                WeightUnit.LB,
            ),
        )
    }

    private fun topSet(sessionId: Long, exerciseId: String, weightLb: Double) =
        TopSetRow(sessionId, 0, exerciseId, weightLb)

    private fun summary(sessionId: Long, year: Int, month: Int, day: Int, setCount: Int): SessionSummaryRow {
        val completedAt = LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return SessionSummaryRow(
            WorkoutSessionEntity(sessionId, "B", "Lower", null, completedAt, 235),
            setCount,
        )
    }
}
