package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.data.OldWizardPlans
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.SessionTonnageRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
            bodyweightLb = 200.0,
        ),
        setCount = 8,
    )

    private val squat = JournalBuilder.MainLift("bb_back_squat", "Barbell Back Squat", dayIndex = 0, goalLb = 235.0)
    private val bench = JournalBuilder.MainLift("bb_bench", "Barbell Bench Press", dayIndex = 1, goalLb = 195.0)

    @Test fun `cardio trends follow each activity and planned end`() {
        val (sessions, sets) = cardioTrendHistoryFixture()
        val cards = JournalBuilder.cardioTrends(sessions.reversed(), sets, zone)
        assertEquals(listOf(Measure.Time(900), Measure.Distance(2000)), cards.map { it.end })
        assertEquals(listOf(3412f, 3470f), cards[0].points.map { it.value })
        assertEquals(listOf(true, true), cards[0].points.map { it.newHigh })
        assertEquals(listOf(492f, 485f, 477f), cards[1].points.map { it.value })
        assertEquals(listOf(true, true, true), cards[1].points.map { it.newHigh })
        assertEquals(3412, cards[0].first)
        assertEquals(3470, cards[0].latest)
        assertEquals(2, cards[0].dayIndex)
        assertEquals(LocalDate.of(2026, 11, 4), cards[0].points.first().date)
        assertTrue(cards[0].axisMin < 3412f && cards[0].axisMax > 3470f)
        val different = sessions.last().copy(id = 10, activity = "ROAD_RUN")
        assertEquals(cards, JournalBuilder.cardioTrends(sessions + different,
            sets + (10L to sets.getValue(4)), zone))
    }

    @Test fun `the trend skips results that do not count`() {
        val (sessions, sets) = cardioTrendHistoryFixture()
        val extra = sessions[4].copy(id = 10, completedAt = sessions.last().completedAt + 1)
        val short = sets.getValue(5).single().copy(status = CardioSetStatus.SHORTENED)
        val cards = JournalBuilder.cardioTrends(sessions + extra, sets + (10L to listOf(short)), zone)
        assertEquals(listOf(3412f, 3470f), cards.first().points.map { it.value })
        val worse = short.copy(status = CardioSetStatus.FINISHED, metresAtEnd = 3400)
        val tied = extra.copy(id = 11)
        val continued = JournalBuilder.cardioTrends(sessions + extra + tied,
            sets + (10L to listOf(worse)) + (11L to sets.getValue(5)), zone).first()
        assertEquals(listOf(true, true, false, false), continued.points.map { it.newHigh })
    }

    @Test fun `trend cards stop at four`() {
        val (sessions, sets) = cardioTrendHistoryFixture()
        val ends = (1..6).flatMap { minutes ->
            List(2) { sets.getValue(4).single().copy(spec = CardioSetSpec(CardioRole.WORK, Measure.Time(minutes * 60)),
                elapsedSeconds = minutes * 60, secondsAtEnd = minutes * 60) }
        }
        val many = ends.mapIndexed { index, result ->
            sessions.first().copy(id = index + 1L, completedAt = index.toLong(), seconds = result.elapsedSeconds)
        }
        val cards = JournalBuilder.cardioTrends(many,
            ends.mapIndexed { index, result -> index + 1L to listOf(result) }.toMap(), zone)
        assertEquals(listOf(360, 300, 240, 180), cards.map { (it.end as Measure.Time).seconds })
    }

    @Test fun `cardio trends share the legacy activity fallback`() {
        val (sessions, sets) = cardioTrendHistoryFixture()
        val legacy = sessions.take(2).map { it.copy(activity = null, mode = "OUTDOOR_RUN", dayId = null) }
        val card = JournalBuilder.cardioTrends(legacy, sets, zone).single()
        assertEquals(CardioActivity.ROAD_RUN, card.activity)
        assertEquals(0, card.dayIndex)
        assertEquals(listOf(492f, 485f), card.points.map { it.value })
        assertTrue(JournalBuilder.cardioTrends(legacy.map { it.copy(mode = "NONE") }, sets, zone).isEmpty())
    }

    @Test fun `a distance end card is inverted`() {
        val (sessions, sets) = cardioTrendHistoryFixture()
        val cards = JournalBuilder.cardioTrends(sessions, sets, zone)
        assertTrue(cards.single { it.end is Measure.Distance }.inverted)
        assertFalse(cards.single { it.end is Measure.Time }.inverted)
    }

    // --- main lifts -----------------------------------------------------------

    @Test
    fun `a straight-set main lift keeps its card`() {
        val goalsLb = emptyMap<String, Double>()
        val exercise = mainExercise(
            "bb_back_squat", structure = SetStructure.STRAIGHT, targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(45.0, 5)),
        )
        val program = Program(listOf(ProgramDay("A", "Lower", "", listOf(exercise))))
        val main = JournalBuilder.mainLifts(program, ExerciseCatalog.CODE_ONLY, goalsLb).single()
        assertEquals("bb_back_squat", main.exerciseId)
        assertNull(main.goalLb)
    }

    @Test
    fun `equipment-substituted novice mains keep their history cards`() {
        val plan = OldWizardPlans.MACHINE_NOVICE
        val program = plan.program
        val catalog = ExerciseCatalog.CODE_ONLY
        val mainIds = plan.mains
        val hackSquat = program.days.first().exercises.first()
        assertEquals("hack_squat", hackSquat.exerciseId)
        assertEquals(SetStructure.STRAIGHT, hackSquat.structure)
        assertTrue(catalog.get(hackSquat.exerciseId).goal == GoalSource.Weighted)

        val mains = JournalBuilder.mainLifts(program, catalog, emptyMap(), mainIds.toSet())
        assertEquals(mainIds, mains.map { it.exerciseId })
        val history = mainIds.map { top(it, today, 50.0) }
        val cards = JournalBuilder.trajectories(mains, history, WeightUnit.LB, zone)

        assertEquals(mainIds, cards.map { it.exerciseId })
        val card = cards.single { it.exerciseId == "hack_squat" }
        assertNull(card.goalValue)
        assertEquals(listOf(50f), card.points.map { it.value })
    }

    @Test
    fun mainLiftsKeepTheirDayLetterAccentsWhenADayIsRemoved() {
        val program = Program(listOf(
            ProgramDay("A", "Lower", "", listOf(mainExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF))),
            ProgramDay("C", "Upper", "", listOf(mainExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF))),
        ))
        val mains = JournalBuilder.mainLifts(program, ExerciseCatalog.CODE_ONLY, emptyMap())

        assertEquals(listOf(0, 2), mains.map { it.dayIndex })
    }

    @Test
    fun mainLifts_reads_the_generated_program_mains_with_their_read_only_goals() {
        val plan = OldWizardPlans.DEFAULT
        val generated = plan.program
        val goalsLb = emptyMap<String, Double>()
        val mains = JournalBuilder.mainLifts(generated, ExerciseCatalog(emptyList()), goalsLb,
            plan.mains.toSet())

        assertTrue(mains.isNotEmpty(), "the generated program has main lifts")
        val squatLift = mains.first { it.exerciseId == "bb_back_squat" }
        assertNull(squatLift.goalLb)
        assertEquals(0, squatLift.dayIndex, "day A's index is the accent key")
        assertEquals(mains.map { it.exerciseId }.distinct(), mains.map { it.exerciseId })
    }

    @Test
    fun editing_starting_weight_changes_working_sets_without_replacing_the_goal() {
        val catalog = ExerciseCatalog.CODE_ONLY
        val goalsLb = emptyMap<String, Double>()
        for (structure in listOf(SetStructure.RAMP_TOP, SetStructure.RAMP_TOP_BACKOFF)) {
            val exercise = mainExercise("bb_back_squat", structure = structure)
            val day = ProgramDay("A", "Lower", "", listOf(exercise))
            val before = JournalBuilder.mainLifts(Program(listOf(day)), catalog, goalsLb).single()
            val edited = exercise.copy(prescription = Prescription(PrescribedTarget.LoadReps(45.0, 5)))
            val program = Program(listOf(day.copy(exercises = listOf(edited))))

            val after = JournalBuilder.mainLifts(program, catalog, goalsLb).single()
            val sets = assertNotNull(SetSeeder.seedPrescribed(edited.targetSets, requireNotNull(edited.prescription), edited.structure,
                equipment = catalog.get(edited.exerciseId).equipment))
            val card = JournalBuilder.trajectories(
                listOf(after), listOf(top(edited.exerciseId, today, 45.0)), WeightUnit.LB, zone,
            ).single()

            assertEquals(45.0, sets.single { it.kind == SetKind.TOP }.weightLb)
            assertEquals(before, after)
            assertNull(after.goalLb)
            assertNull(card.goalValue)
            assertNull(card.goalLabel)
            assertFalse(card.goalMet)
        }
    }

    @Test
    fun `an edited goal is the history card goal line`() {
        val goalsLb = mapOf("bb_back_squat" to 200.0)
        val exercise = mainExercise("bb_back_squat", SetStructure.RAMP_TOP_BACKOFF,
            prescription = Prescription(PrescribedTarget.LoadReps(45.0, 5)))
        val program = Program(listOf(ProgramDay("A", "Lower", "", listOf(exercise))))
        val mains = JournalBuilder.mainLifts(program, ExerciseCatalog.CODE_ONLY, goalsLb)
        val card = JournalBuilder.trajectories(mains, listOf(top(exercise.exerciseId, today, 45.0)), WeightUnit.LB, zone).single()
        assertEquals(200.0, mains.single().goalLb)
        assertEquals(200f, card.goalValue)
        assertEquals("200", card.goalLabel)
        assertFalse(card.goalMet)
    }

    @Test
    fun weighted_prescriptions_keep_trajectories_after_swaps_to_reps_or_time_goals() {
        val catalog = ExerciseCatalog.CODE_ONLY
        val goalsLb = emptyMap<String, Double>()
        for (structure in listOf(SetStructure.RAMP_TOP, SetStructure.RAMP_TOP_BACKOFF)) {
            val exercise = mainExercise(
                "weighted_pullup", structure = structure,
                prescription = Prescription(PrescribedTarget.LoadReps(25.0, 5)),
            )
            for (replacementId in listOf("pullup", "dead_hang")) {
                val swapped = exercise.copy(exerciseId = replacementId)
                val day = ProgramDay("A", "Pull", "", listOf(swapped))
                val sets = assertNotNull(SetSeeder.seedPrescribed(swapped.targetSets, requireNotNull(swapped.prescription), swapped.structure,
                    equipment = catalog.get(replacementId).equipment))
                val mains = JournalBuilder.mainLifts(Program(listOf(day)), catalog, goalsLb)
                val card = JournalBuilder.trajectories(
                    mains,
                    listOf(top(replacementId, today.minusDays(5), 30.0), top(replacementId, today, 35.0)),
                    WeightUnit.LB, zone,
                ).single()

                assertEquals(25.0, sets.single { it.kind == SetKind.TOP }.weightLb)
                assertEquals(replacementId, mains.single().exerciseId)
                assertNull(mains.single().goalLb)
                assertNull(card.goalValue)
                assertNull(card.goalLabel)
                assertFalse(card.goalMet)
                assertEquals(listOf(30f, 35f), card.points.map { it.value })
                assertEquals(listOf(true, true), card.points.map { it.newHigh })
                assertEquals("35", card.latestLabel)
                assertTrue(card.plotted)
                assertTrue(card.axisMin > 25f && card.axisMin < 30f)
                assertTrue(card.axisMax > 35f)
                assertTrue(JournalBuilder.mainLifts(
                    Program(listOf(day.copy(exercises = listOf(swapped.copy(prescription = null))))), catalog, goalsLb,
                ).isEmpty())
            }
        }
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
                    mainExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, prescription = Prescription(target)),
                    mainExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF),
                ),
            )))
            val history = listOf(top("bb_back_squat", today, 235.0), top("bb_bench", today, 195.0))

            val mains = JournalBuilder.mainLifts(program, ExerciseCatalog.CODE_ONLY, emptyMap())

            val expectedIds = if (target is PrescribedTarget.LoadReps) listOf("bb_back_squat", "bb_bench") else listOf("bb_bench")
            assertEquals(expectedIds, mains.map { it.exerciseId })
            assertEquals(expectedIds, JournalBuilder.trajectories(mains, history, WeightUnit.LB, zone).map { it.exerciseId })
            if (target is PrescribedTarget.LoadReps) {
                assertNull(mains.first().goalLb)
            }
        }
    }

    @Test fun `a straight weighted main without a goal keeps its card`() {
        val program = Program(listOf(ProgramDay("A", "Lower", "", listOf(mainExercise("goblet_squat")))))
        val lift = JournalBuilder.mainLifts(program, ExerciseCatalog.CODE_ONLY, emptyMap()).single()
        assertEquals("goblet_squat", lift.exerciseId)
        assertNull(lift.goalLb)
    }

    @Test fun `a reps or timed main has no card`() {
        val program = Program(listOf(ProgramDay("A", "Bodyweight", "", listOf(mainExercise("pullup"), mainExercise("plank")))))
        assertTrue(JournalBuilder.mainLifts(program, ExerciseCatalog.CODE_ONLY, emptyMap()).isEmpty())
    }

    @Test fun `role decides membership`() {
        val accessory = mainExercise("bb_bench", SetStructure.RAMP_TOP_BACKOFF).let { slot ->
            slot.copy(rule = requireNotNull(slot.rule).copy(role = SlotRole.ACCESSORY))
        }
        val program = Program(listOf(ProgramDay("A", "Mixed", "", listOf(accessory, mainExercise("goblet_squat")))))
        assertEquals(listOf("goblet_squat"), JournalBuilder.mainLifts(program, ExerciseCatalog.CODE_ONLY, emptyMap()).map { it.exerciseId })
    }

    private fun mainExercise(id: String, structure: SetStructure = SetStructure.STRAIGHT, targetSets: Int = 3,
                             prescription: Prescription? = null): ProgramExercise =
        ProgramExercise(id, structure, targetSets, prescription = prescription,
            rule = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH, structure, 5, ExerciseLibrary.get(id).pattern))

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

    // --- weekly distance ------------------------------------------------------

    private val distanceToday = LocalDate.of(2026, 11, 5)

    private fun cardioSession(
        id: Long,
        activity: CardioActivity? = CardioActivity.ROAD_RUN,
        date: LocalDate = distanceToday,
    ) = CardioSessionEntity(
        id = id, dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Run",
        startedAt = millis(date) - 60_000, completedAt = millis(date), seconds = 60,
        stepsCompleted = 1, activity = activity?.name,
    )

    private fun cardioResult(metres: Int?, status: CardioSetStatus = CardioSetStatus.FINISHED) = CardioSetResult(
        spec = CardioSetSpec(CardioRole.WORK, Measure.Time(60)),
        status = status, elapsedSeconds = 60, metres = metres,
        distanceSource = metres?.let { DistanceSource.MANUAL },
    )

    @Test
    fun `running totals add every run this week`() {
        val sessions = listOf(
            cardioSession(1, CardioActivity.ROAD_RUN, LocalDate.of(2026, 11, 3)),
            cardioSession(2, CardioActivity.TREADMILL_RUN),
        )
        val sets = mapOf(1L to listOf(cardioResult(3219)), 2L to listOf(cardioResult(10_300)))

        val rows = JournalBuilder.weeklyDistance(sessions, sets, distanceToday, zone)

        assertEquals(listOf(WeeklyDistance(CardioFamily.RUN, 13_519, 0)), rows)
        assertEquals("8.4 mi", DistanceUnit.MI.format(rows.single().metres.toInt()))
    }

    @Test
    fun `a run without distance is counted, not guessed`() {
        val sessions = listOf(
            cardioSession(1, CardioActivity.ROAD_RUN, LocalDate.of(2026, 11, 3)),
            cardioSession(2, CardioActivity.TREADMILL_RUN),
            cardioSession(3, CardioActivity.TRAIL_RUN, LocalDate.of(2026, 11, 4)),
        )
        val sets = mapOf(
            1L to listOf(cardioResult(3219)), 2L to listOf(cardioResult(10_300)),
            3L to listOf(cardioResult(null)),
        )

        assertEquals(
            listOf(WeeklyDistance(CardioFamily.RUN, 13_519, 1)),
            JournalBuilder.weeklyDistance(sessions, sets, distanceToday, zone),
        )
    }

    @Test
    fun `skipped sets do not mark a session missing`() {
        val sets = mapOf(1L to listOf(
            cardioResult(5000), cardioResult(null, CardioSetStatus.SKIPPED),
            cardioResult(1000, CardioSetStatus.SKIPPED),
        ))

        assertEquals(
            listOf(WeeklyDistance(CardioFamily.RUN, 5000, 0)),
            JournalBuilder.weeklyDistance(listOf(cardioSession(1)), sets, distanceToday, zone),
        )
    }

    @Test
    fun `last week is left out`() {
        val session = cardioSession(1, date = LocalDate.of(2026, 11, 1))

        assertEquals(
            emptyList(),
            JournalBuilder.weeklyDistance(listOf(session), mapOf(1L to listOf(cardioResult(5000))), distanceToday, zone),
        )
    }

    @Test
    fun `a legacy summary counts as missing`() {
        assertEquals(
            listOf(WeeklyDistance(CardioFamily.RUN, 0, 1)),
            JournalBuilder.weeklyDistance(listOf(cardioSession(1, activity = null)), emptyMap(), distanceToday, zone),
        )
    }

    @Test
    fun `families stay apart`() {
        val sessions = listOf(cardioSession(1, CardioActivity.ROWER), cardioSession(2, CardioActivity.OUTDOOR_RIDE))
        val sets = mapOf(1L to listOf(cardioResult(2000)), 2L to listOf(cardioResult(20_000)))

        assertEquals(
            listOf(WeeklyDistance(CardioFamily.RIDE, 20_000, 0), WeeklyDistance(CardioFamily.ROW, 2000, 0)),
            JournalBuilder.weeklyDistance(sessions, sets, distanceToday, zone),
        )
    }

    @Test
    fun `no cardio this week`() {
        assertEquals(emptyList(), JournalBuilder.weeklyDistance(emptyList(), emptyMap(), distanceToday, zone))
    }

    @Test
    fun `week boundaries use the session date in the supplied zone`() {
        val localZone = ZoneId.of("America/Chicago")
        val sessions = listOf(
            cardioSession(1).copy(completedAt = Instant.parse("2026-11-02T05:59:59Z").toEpochMilli()),
            cardioSession(2).copy(completedAt = Instant.parse("2026-11-02T06:00:00Z").toEpochMilli()),
            cardioSession(3).copy(completedAt = Instant.parse("2026-11-09T05:59:59Z").toEpochMilli()),
            cardioSession(4).copy(completedAt = Instant.parse("2026-11-09T06:00:00Z").toEpochMilli()),
        )
        val sets = sessions.associate { it.id to listOf(cardioResult(1000)) }

        assertEquals(
            listOf(WeeklyDistance(CardioFamily.RUN, 2000, 0)),
            JournalBuilder.weeklyDistance(sessions, sets, distanceToday, localZone),
        )
    }

    @Test
    fun `unknown activity falls back to mode and unknown modes are left out`() {
        val sessions = listOf(
            cardioSession(1).copy(activity = "FUTURE_ACTIVITY"),
            cardioSession(2, activity = null).copy(mode = "FUTURE_MODE"),
            cardioSession(3, activity = null).copy(mode = "NONE"),
        )

        assertEquals(
            listOf(WeeklyDistance(CardioFamily.RUN, 0, 1)),
            JournalBuilder.weeklyDistance(sessions, emptyMap(), distanceToday, zone),
        )
    }

    @Test
    fun `partial sessions keep recorded distance and count missing distance once`() {
        val sets = mapOf(1L to listOf(
            cardioResult(2000, CardioSetStatus.SHORTENED),
            cardioResult(null), cardioResult(null, CardioSetStatus.SHORTENED),
        ))

        assertEquals(
            listOf(WeeklyDistance(CardioFamily.RUN, 2000, 1)),
            JournalBuilder.weeklyDistance(listOf(cardioSession(1)), sets, distanceToday, zone),
        )
    }

    @Test
    fun `skipped only and recorded zero sessions do not count as missing`() {
        val sessions = listOf(cardioSession(1), cardioSession(2))
        val sets = mapOf(1L to listOf(cardioResult(null, CardioSetStatus.SKIPPED)), 2L to listOf(cardioResult(0)))

        assertEquals(
            listOf(WeeklyDistance(CardioFamily.RUN, 0, 0)),
            JournalBuilder.weeklyDistance(sessions, sets, distanceToday, zone),
        )
    }

    @Test
    fun `distance totals retain values beyond the integer range`() {
        val sets = mapOf(1L to listOf(cardioResult(Int.MAX_VALUE), cardioResult(Int.MAX_VALUE)))

        assertEquals(
            listOf(WeeklyDistance(CardioFamily.RUN, 2L * Int.MAX_VALUE, 0)),
            JournalBuilder.weeklyDistance(listOf(cardioSession(1)), sets, distanceToday, zone),
        )
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
