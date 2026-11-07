package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.setup.GoalPreviewItem
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanScreenBuilderTest {
    @Test fun `cardio goal progress comes from matching history`() {
        val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960)
        val plan = PlanEntity(1, "Running", PlanOrigin.AUTHORED.name, 0, 0, goalJson = PlanGoalJson.encode(goal))
        val result = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(3219)), CardioSetStatus.FINISHED, 984)
        val state = PlanScreenBuilder.buildUiState(plan, Program(emptyList()), WizardAnswers(),
            ExerciseCatalog.CODE_ONLY, WeightUnit.LB, listOf(CardioActivity.ROAD_RUN to listOf(result)))
        assertEquals(PlanGoalUi(goal, 984, false), state.goal)
        assertEquals(PlanGoalUi(goal, null, false), PlanScreenBuilder.buildUiState(
            plan, Program(emptyList()), WizardAnswers(), ExerciseCatalog.CODE_ONLY, WeightUnit.LB).goal)
        assertNull(PlanScreenBuilder.buildUiState(plan.copy(goalJson = "junk"), Program(emptyList()),
            WizardAnswers(), ExerciseCatalog.CODE_ONLY, WeightUnit.LB).goal)
    }

    private val config = LifterConfig()
    private val catalog = ExerciseCatalog.CODE_ONLY

    @Test
    fun days_preserve_rotation_order_count_slots_and_rounds_and_mark_one_next_day() {
        val program = Program(
            listOf(
                day("A", "Full body", 3, 4),
                day("B", "Lower body", 2, 5, 3),
                day("C", "Upper body", 4),
            ),
        )

        val rows = PlanScreenBuilder.days(program, nextDayId = "B", config = config, catalog = catalog)

        assertEquals(listOf("A", "B", "C"), rows.map { it.dayId })
        assertEquals(listOf(0, 1, 2), rows.map { it.dayIndex })
        assertEquals(listOf(2, 3, 1), rows.map { it.liftCount })
        assertEquals(listOf(7, 10, 4), rows.map { it.setCount })
        assertEquals(listOf(false, true, false), rows.map { it.isNext })
    }

    @Test
    fun days_handle_a_six_day_program_with_indices_zero_through_five() {
        val program = Program(
            (0..5).map { index ->
                day(('A'.code + index).toChar().toString(), "Day ${index + 1}", index + 1)
            },
        )

        val rows = PlanScreenBuilder.days(program, nextDayId = "F", config = config, catalog = catalog)

        assertEquals((0..5).toList(), rows.map { it.dayIndex })
        assertEquals(6, rows.size)
        assertEquals(1, rows.count { it.isNext })
        assertTrue(rows.last().isNext)
        assertFalse(rows.first().isNext)
    }

    @Test
    fun weightedMain_countsTheSeededRampTopAndBackOffRows() {
        val program = Program(
            listOf(
                ProgramDay(
                    id = "A",
                    title = "Day A",
                    emphasisLine = "",
                    exercises = listOf(
                        ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 3),
                        ProgramExercise("ez_curl", targetSets = 4),
                    ),
                ),
            ),
        )

        val row = PlanScreenBuilder.days(
            program,
            nextDayId = "A",
            config = config,
            catalog = catalog,
        ).single()

        assertEquals(10, row.setCount)
        assertTrue(row.setCount > program.strengthDays.single().exercises.sumOf { it.targetSets })
    }

    @Test
    fun daysIncludeStandaloneCardioAndUseLetterAccentsForEveryKind() {
        val program = Program(listOf(
            day("A", "Lower", 3),
            day("C1", "Cardio", 2).copy(kind = ProgramDayKind.CARDIO),
            day("B", "Upper", 3),
            day("C2", "Cardio", 2).copy(kind = ProgramDayKind.CARDIO),
        ))
        val rows = PlanScreenBuilder.days(program, "B", config, catalog)
        assertEquals(listOf("A", "C1", "B", "C2"), rows.map { it.dayId })
        assertEquals(listOf(0, 2, 1, 2), rows.map { it.dayIndex })
        assertEquals(program.days.map { it.kind }, rows.map { it.kind })
        assertEquals(listOf("B"), rows.filter { it.isNext }.map { it.dayId })
    }

    @Test
    fun generatedPlansShowTheFourPinnedGoalsAndTheirEmphasis() {
        val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0)
        val state = PlanScreenBuilder.buildUiState(
            plan, ProgramGenerator.generate(WizardAnswers()), WizardAnswers(), catalog, WeightUnit.LB,
        )
        assertEquals(listOf(
            GoalPreviewItem("Barbell Back Squat", "235", false),
            GoalPreviewItem("Barbell Bench Press", "195", false),
            GoalPreviewItem("Trap-Bar Deadlift", "255", false),
            GoalPreviewItem("Incline DB Press", "75", true),
        ), state.goals.map { GoalPreviewItem(it.name, it.display, it.perHand) })
        assertTrue(state.goals.all { it.calculated == null })
        assertEquals(GoalEmphasis.BALANCED, state.emphasis)
    }

    @Test
    fun `an edited goal shows its calculated value`() {
        val answers = WizardAnswers(config = LifterConfig(goalsLb = mapOf("bb_back_squat" to 200.0)))
        val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0)
        val state = PlanScreenBuilder.buildUiState(
            plan, ProgramGenerator.generate(answers), answers, catalog, WeightUnit.LB,
        )
        val squat = state.goals.single { it.exerciseId == "bb_back_squat" }
        assertEquals("200", squat.display)
        assertEquals(200.0, squat.goalLb, 0.0)
        assertEquals("235", squat.calculated)
        assertNull(state.goals.single { it.exerciseId == "bb_bench" }.calculated)
        val kg = PlanScreenBuilder.buildUiState(
            plan, ProgramGenerator.generate(answers), answers, catalog, WeightUnit.KG,
        )
        assertEquals(WeightUnit.KG, kg.unit)
        assertEquals(200.0, kg.goals.single { it.exerciseId == "bb_back_squat" }.goalLb, 0.0)
    }

    @Test
    fun `goals name only lifts the plan has`() {
        val answers = WizardAnswers(equipment = Equipment.entries.toSet() - setOf(Equipment.BARBELL, Equipment.TRAP_BAR))
        val program = ProgramGenerator.generate(answers)
        val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0)
        val state = PlanScreenBuilder.buildUiState(plan, program, answers, catalog, WeightUnit.LB)
        val names = state.goals.map { it.name }
        val plannedNames = program.days.flatMap { day -> day.exercises.map { ExerciseLibrary.get(it.exerciseId).name } }

        assertTrue(names.isNotEmpty())
        assertFalse("Barbell Back Squat" in names)
        assertFalse("Trap-Bar Deadlift" in names)
        assertTrue(names.all { it in plannedNames })
    }

    @Test
    fun generatedPlansWithoutLiftsHideGoalsAndEmphasisRegardlessOfRecipe() {
        val answers = WizardAnswers(cardioFirst = CardioFirst())
        val program = ProgramGenerator.generate(answers)
        val plan = PlanEntity(1, "Running", PlanOrigin.GENERATED.name, 0, 0)

        for (recipe in listOf(answers, WizardAnswers())) {
            val state = PlanScreenBuilder.buildUiState(plan, program, recipe, catalog, WeightUnit.LB)

            assertTrue(state.hasProgram)
            assertEquals(listOf("Easy", "Intervals", "Long"), state.days.map { it.title })
            assertTrue(state.days.all { it.liftCount == 0 && it.cardioSetCount > 0 })
            assertTrue(state.goals.isEmpty())
            assertNull(state.emphasis)
        }
    }

    @Test
    fun authoredAndEmptyGeneratedPlansHideGoalsAndEmphasis() {
        val plan = PlanEntity(1, "Authored", PlanOrigin.AUTHORED.name, 0, 0)
        val authored = PlanScreenBuilder.buildUiState(
            plan, Program(listOf(day("A", "Day A"))), WizardAnswers(), catalog, WeightUnit.LB,
        )
        assertTrue(authored.goals.isEmpty())
        assertNull(authored.emphasis)
        val empty = PlanScreenBuilder.buildUiState(
            plan.copy(origin = PlanOrigin.GENERATED.name), Program(emptyList()), WizardAnswers(), catalog, WeightUnit.LB,
        )
        assertTrue(empty.goals.isEmpty())
        assertNull(empty.emphasis)
    }

    private fun day(id: String, title: String, vararg rounds: Int) = ProgramDay(
        id = id,
        title = title,
        emphasisLine = "",
        exercises = rounds.mapIndexed { index, count ->
            ProgramExercise(exerciseId = "exercise-$id-$index", targetSets = count)
        },
    )
}
