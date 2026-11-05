package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.SessionTonnageRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The journal's pure derivations (docs/briefs/journal.md §3). Everything the
 * three sections show is decided here, so this is where the contract lives:
 * new-high markers, the goal-met flip, empty weeks staying empty, month grids,
 * and — throughout — empty history collapsing to nothing at all.
 */
class JournalBuilderTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 7, 15) // a Wednesday

    private fun millis(date: LocalDate) = date.atTime(LocalTime.NOON).toInstant(zone).toEpochMilli()

    private fun top(exerciseId: String, date: LocalDate, weightLb: Double, sessionId: Long = 0) =
        TopSetRow(sessionId, millis(date), exerciseId, weightLb)

    private fun tonnage(date: LocalDate, tonnageLb: Double, sessionId: Long = 0) =
        SessionTonnageRow(sessionId, millis(date), tonnageLb)

    private fun summary(id: Long, dayId: String, date: LocalDate, atHour: Int = 12) = SessionSummaryRow(
        session = WorkoutSessionEntity(
            id = id,
            dayId = dayId,
            dayTitle = "Day $dayId",
            startedAt = null,
            completedAt = date.atTime(atHour, 0).toInstant(zone).toEpochMilli(),
            bodyweightLb = 200,
        ),
        setCount = 8,
    )

    private val squat = JournalBuilder.MainLift("bb_back_squat", "Barbell Back Squat", dayIndex = 0, goalLb = 235.0)
    private val bench = JournalBuilder.MainLift("bb_bench", "Barbell Bench Press", dayIndex = 1, goalLb = 195.0)

    // --- main lifts -----------------------------------------------------------

    @Test
    fun mainLiftsKeepTheirDayLetterAccentsWhenADayIsRemoved() {
        val program = Program(listOf(
            ProgramDay("A", "Lower", "", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF))),
            ProgramDay("C", "Upper", "", listOf(ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF))),
        ))
        val mains = JournalBuilder.mainLifts(program, ExerciseCatalog.CODE_ONLY, LifterConfig())

        assertEquals(listOf(0, 2), mains.map { it.dayIndex })
    }

    @Test
    fun mainLifts_reads_the_generated_program_mains_with_their_read_only_goals() {
        val generated = ProgramGenerator.generate(WizardAnswers())
        val cfg = LifterConfig()
        val mains = JournalBuilder.mainLifts(generated, ExerciseCatalog(emptyList()), cfg)

        assertTrue(mains.isNotEmpty(), "the generated program has main lifts")
        // Spec §11's pinned squat GOAL, read (never recomputed) through GoalCalculator.
        val squatLift = mains.first { it.exerciseId == "bb_back_squat" }
        assertEquals(235.0, squatLift.goalLb)
        assertEquals(0, squatLift.dayIndex, "day A's index is the accent key")
        assertEquals(mains.map { it.exerciseId }.distinct(), mains.map { it.exerciseId })
    }

    @Test
    fun mainLifts_includes_only_weighted_prescriptions_under_ramped_structures() {
        val targets = listOf(
            PrescribedTarget.LoadReps(37.5, 8), PrescribedTarget.Reps(12),
            PrescribedTarget.Hold(45, 25.0), PrescribedTarget.RepsInTime(120, 50),
        )
        targets.forEach { target ->
            val program = Program(listOf(ProgramDay(
                "A", "Mixed", "", listOf(
                    ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, prescription = Prescription(target)),
                    ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF),
                ),
            )))
            val history = listOf(top("bb_back_squat", today, 235.0), top("bb_bench", today, 195.0))

            val mains = JournalBuilder.mainLifts(program, ExerciseCatalog.CODE_ONLY, LifterConfig())

            val expectedIds = if (target is PrescribedTarget.LoadReps) listOf("bb_back_squat", "bb_bench") else listOf("bb_bench")
            assertEquals(expectedIds, mains.map { it.exerciseId })
            assertEquals(expectedIds, JournalBuilder.trajectories(mains, history, WeightUnit.LB, zone).map { it.exerciseId })
            if (target is PrescribedTarget.LoadReps) {
                assertEquals(target.lb, mains.first().goalLb)
            }
        }
    }

    // --- trajectory -----------------------------------------------------------

    @Test
    fun trajectory_marks_only_the_points_that_set_an_all_time_high() {
        val rows = listOf(
            top("bb_back_squat", today.minusDays(20), 200.0),
            top("bb_back_squat", today.minusDays(15), 200.0),
            top("bb_back_squat", today.minusDays(10), 210.0),
            top("bb_back_squat", today.minusDays(5), 205.0),
            top("bb_back_squat", today, 215.0),
        )
        val card = JournalBuilder.trajectories(listOf(squat), rows, WeightUnit.LB, zone).single()

        assertEquals(listOf(200f, 200f, 210f, 205f, 215f), card.points.map { it.value })
        assertEquals(listOf(true, false, true, false, true), card.points.map { it.newHigh })
        assertTrue(card.plotted)
        assertEquals("215", card.latestLabel)
    }

    @Test
    fun trajectory_marks_only_strict_weight_improvements_after_the_first_session() {
        val weights = listOf(225.0, 235.0, 230.0, 235.0, 245.0)
        val rows = weights.mapIndexed { index, weight ->
            top("bb_back_squat", today.minusDays((weights.lastIndex - index).toLong()), weight)
        }
        val card = JournalBuilder.trajectories(listOf(squat), rows, WeightUnit.LB, zone).single()

        assertEquals(listOf(225f, 235f, 230f, 235f, 245f), card.points.map { it.value })
        assertEquals(listOf(true, true, false, false, true), card.points.map { it.newHigh })
    }

    @Test
    fun changed_archived_top_set_changes_the_trajectory_point() {
        val date = today.minusDays(3)
        val before = JournalBuilder.trajectories(
            listOf(squat), listOf(top("bb_back_squat", date, 205.0)), WeightUnit.LB, zone,
        ).single()
        val after = JournalBuilder.trajectories(
            listOf(squat), listOf(top("bb_back_squat", date, 215.0)), WeightUnit.LB, zone,
        ).single()

        assertEquals(205f, before.points.single().value)
        assertEquals(215f, after.points.single().value)
    }

    @Test
    fun trajectory_goal_line_flips_to_met_only_once_the_goal_is_reached() {
        val below = JournalBuilder.trajectories(
            listOf(squat),
            listOf(top("bb_back_squat", today.minusDays(3), 230.0)),
            WeightUnit.LB,
            zone,
        ).single()
        assertEquals("235", below.goalLabel)
        assertFalse(below.goalMet)

        val met = JournalBuilder.trajectories(
            listOf(squat),
            listOf(top("bb_back_squat", today.minusDays(3), 235.0)),
            WeightUnit.LB,
            zone,
        ).single()
        assertTrue(met.goalMet, "hitting the goal exactly counts as meeting it")
    }

    @Test
    fun trajectory_with_one_session_is_not_plotted_but_still_states_the_numbers() {
        val card = JournalBuilder.trajectories(
            listOf(squat),
            listOf(top("bb_back_squat", LocalDate.of(2026, 5, 4), 190.0)),
            WeightUnit.LB,
            zone,
        ).single()

        assertFalse(card.plotted)
        assertEquals("190", card.latestLabel)
        assertEquals(LocalDate.of(2026, 5, 4), card.points.single().date)
    }

    @Test
    fun trajectory_points_carry_each_sessions_date() {
        val rows = listOf(
            top("bb_back_squat", LocalDate.of(2026, 5, 4), 190.0),
            top("bb_back_squat", LocalDate.of(2026, 6, 1), 200.0),
            top("bb_back_squat", LocalDate.of(2026, 7, 1), 205.0),
        )
        val card = JournalBuilder.trajectories(listOf(squat), rows, WeightUnit.LB, zone).single()
        assertEquals(
            listOf(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)),
            card.points.map { it.date },
        )
    }

    @Test
    fun trajectory_axis_brackets_both_the_points_and_the_goal_line() {
        val rows = listOf(
            top("bb_back_squat", today.minusDays(5), 180.0),
            top("bb_back_squat", today, 200.0),
        )
        val card = JournalBuilder.trajectories(listOf(squat), rows, WeightUnit.LB, zone).single()

        assertTrue(card.axisMin < 180f, "the lightest top set has headroom below it")
        assertTrue(card.axisMax > 235f, "the goal line above every point is still on screen")
    }

    @Test
    fun trajectory_flat_series_still_has_a_usable_axis() {
        val rows = listOf(
            top("bb_back_squat", today.minusDays(5), 235.0),
            top("bb_back_squat", today, 235.0),
        )
        val card = JournalBuilder.trajectories(listOf(squat), rows, WeightUnit.LB, zone).single()

        assertTrue(card.axisMax > card.axisMin)
    }

    @Test
    fun trajectory_converts_to_the_display_unit() {
        val card = JournalBuilder.trajectories(
            listOf(squat),
            listOf(
                top("bb_back_squat", today.minusDays(5), 220.462),
                top("bb_back_squat", today, 220.462),
            ),
            WeightUnit.KG,
            zone,
        ).single()

        assertEquals(100f, card.points.last().value, 0.01f)
        assertEquals("106.59", card.goalLabel)
        assertEquals(WeightUnit.KG, card.unit)
    }

    @Test
    fun trajectory_skips_lifts_that_were_never_trained_and_empty_history_entirely() {
        val rows = listOf(top("bb_back_squat", today, 200.0))
        val cards = JournalBuilder.trajectories(listOf(squat, bench), rows, WeightUnit.LB, zone)
        assertEquals(listOf("bb_back_squat"), cards.map { it.exerciseId })

        assertTrue(JournalBuilder.trajectories(listOf(squat, bench), emptyList(), WeightUnit.LB, zone).isEmpty())
    }

    // --- volume ---------------------------------------------------------------

    @Test
    fun volume_buckets_by_iso_week_and_keeps_untrained_weeks_empty() {
        val chart = assertNotNull(
            JournalBuilder.volume(
                listOf(
                    tonnage(today, 10_000.0),
                    tonnage(today.minusDays(2), 2_000.0), // same ISO week as today
                    tonnage(today.minusWeeks(2), 6_000.0),
                ),
                WeightUnit.LB,
                today,
                zone,
            ),
        )

        assertEquals(JournalBuilder.VOLUME_WEEKS, chart.weeks.size)
        assertEquals(LocalDate.of(2026, 7, 13), chart.weeks.first().startDate)
        assertEquals(LocalDate.of(2026, 4, 27), chart.weeks.last().startDate)
        assertTrue(chart.weeks.first().trained)
        assertEquals(12_000.0, chart.weeks.first().total)
        assertFalse(chart.weeks[1].trained, "last week is verified to have no sessions")
        assertEquals(0.0, chart.weeks[1].total)
        assertEquals(6_000.0, chart.weeks[2].total)
        assertTrue(chart.weeks.all { it.unit == WeightUnit.LB })
    }

    @Test
    fun volume_keeps_exact_totals_for_two_sessions_of_three_sets_of_five_at_100_lb() {
        val chart = assertNotNull(JournalBuilder.volume(
            listOf(tonnage(today, 3 * 5 * 100.0), tonnage(today.minusDays(1), 3 * 5 * 100.0)),
            WeightUnit.LB, today, zone,
        ))

        assertEquals(3_000.0, chart.weeks.first().total)
        assertEquals(WeightUnit.LB, chart.weeks.first().unit)
    }

    @Test
    fun volume_sums_three_sets_across_two_sessions_to_exactly_1500_lb() {
        val chart = assertNotNull(JournalBuilder.volume(
            listOf(tonnage(today, 2 * 5 * 100.0), tonnage(today.minusDays(1), 1 * 5 * 100.0)),
            WeightUnit.LB, today, zone,
        ))

        assertEquals(1_500.0, chart.weeks.first().total)
    }

    @Test
    fun volume_converts_totals_to_the_display_unit_without_changing_week_boundaries() {
        val chart = assertNotNull(JournalBuilder.volume(
            listOf(tonnage(today, WeightUnit.KG.toLb(1500.0))), WeightUnit.KG, today, zone,
        ))

        assertEquals(1500.0, assertNotNull(chart.weeks.first().total), 0.00001)
        assertEquals(WeightUnit.KG, chart.weeks.first().unit)
        assertEquals(LocalDate.of(2026, 7, 13), chart.weeks.first().startDate)
    }

    @Test
    fun volume_ignores_sessions_outside_the_window_and_returns_null_with_none_inside() {
        assertNull(
            JournalBuilder.volume(listOf(tonnage(today.minusWeeks(20), 9_000.0)), WeightUnit.LB, today, zone),
        )
        assertNull(JournalBuilder.volume(emptyList(), WeightUnit.LB, today, zone))
    }

    // --- calendar -------------------------------------------------------------

    @Test
    fun calendar_lays_out_the_current_month_with_a_monday_first_grid() {
        val month = assertNotNull(
            JournalBuilder.calendar(listOf(summary(1, "A", today)), monthOffset = 0, today = today, zone = zone),
        )

        assertEquals("July 2026", month.title)
        assertEquals(31, month.days.size)
        // 2026-07-01 is a Wednesday: Monday and Tuesday lead the grid.
        assertEquals(2, month.leadingBlanks)
        assertTrue(month.days.single { it.dayOfMonth == 15 }.isToday)
        assertFalse(month.canPageForward, "there is nothing past the current month")
        assertFalse(month.canPageBack, "history starts in this month")
    }

    @Test
    fun calendar_marks_trained_days_with_the_first_session_of_that_day() {
        val month = assertNotNull(
            JournalBuilder.calendar(
                listOf(
                    summary(7, "C", today, atHour = 18),
                    summary(6, "B", today, atHour = 7),
                    summary(5, "A", today.minusDays(2)),
                ),
                monthOffset = 0,
                today = today,
                zone = zone,
            ),
        )

        val trainedToday = month.days.single { it.dayOfMonth == 15 }
        assertEquals("B", trainedToday.dayLetter, "the earlier session names the cell")
        assertEquals(6L, trainedToday.sessionId)
        assertEquals(1, trainedToday.dayIndex)
        assertTrue(trainedToday.moreSessions)

        val single = month.days.single { it.dayOfMonth == 13 }
        assertEquals("A", single.dayLetter)
        assertFalse(single.moreSessions)

        val untrained = month.days.single { it.dayOfMonth == 14 }
        assertNull(untrained.dayLetter)
        assertNull(untrained.sessionId)
    }

    @Test
    fun anUntrainedCellSaysItsDateTodayStateAndThatNothingHappenedThere() {
        val marchToday = LocalDate.of(2026, 3, 14)
        val month = assertNotNull(
            JournalBuilder.calendar(
                listOf(summary(1, "A", marchToday.minusDays(1))),
                monthOffset = 0,
                today = marchToday,
                zone = zone,
            ),
        )

        assertEquals("March 3, no session", month.days.single { it.dayOfMonth == 3 }.label)
        assertEquals(
            "March 14, today, no session",
            month.days.single { it.dayOfMonth == 14 }.label,
        )
    }

    @Test
    fun aTrainedCellSaysItsDateDayAndSessionCount() {
        val marchToday = LocalDate.of(2026, 3, 14)
        val month = assertNotNull(
            JournalBuilder.calendar(
                listOf(
                    summary(1, "A", LocalDate.of(2026, 3, 5)),
                    summary(2, "B", marchToday, atHour = 7),
                    summary(3, "C", marchToday, atHour = 12),
                    summary(4, "D", marchToday, atHour = 18),
                ),
                monthOffset = 0,
                today = marchToday,
                zone = zone,
            ),
        )

        assertEquals(
            "March 5, day A, 1 session",
            month.days.single { it.dayOfMonth == 5 }.label,
        )
        assertEquals(
            "March 14, today, day B, 3 sessions",
            month.days.single { it.dayOfMonth == 14 }.label,
        )
    }

    @Test
    fun calendar_pages_back_to_the_first_session_and_never_forward_past_today() {
        val sessions = listOf(summary(1, "A", LocalDate.of(2026, 5, 20)), summary(2, "B", today))

        val current = assertNotNull(JournalBuilder.calendar(sessions, 0, today, zone))
        assertTrue(current.canPageBack)
        assertFalse(current.canPageForward)

        val june = assertNotNull(JournalBuilder.calendar(sessions, -1, today, zone))
        assertEquals("June 2026", june.title)
        assertTrue(june.canPageBack, "May still has a session")
        assertTrue(june.canPageForward)

        val may = assertNotNull(JournalBuilder.calendar(sessions, -2, today, zone))
        assertEquals("May 2026", may.title)
        assertFalse(may.canPageBack, "the first session's month is the floor")

        // A positive offset can never produce a future month.
        assertEquals("July 2026", assertNotNull(JournalBuilder.calendar(sessions, 3, today, zone)).title)
    }

    @Test
    fun calendar_renders_nothing_without_history() {
        assertNull(JournalBuilder.calendar(emptyList(), 0, today, zone))
    }
}
