package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
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
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.model.MovementPattern
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

    @Test fun `a sprint goal shows no goal row before PR 7`() {
        val plan = PlanEntity(1, "Sprint", PlanOrigin.AUTHORED.name, 0, 0,
            goalJson = PlanGoalJson.encode(PlanGoal.Sprint(100)))
        val state = PlanScreenBuilder.buildUiState(plan, Program(emptyList<ProgramDay>()), WizardAnswers(),
            ExerciseCatalog.CODE_ONLY, WeightUnit.LB)
        assertNull(state.goal)
    }

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

        val rows = PlanScreenBuilder.days(program, nextDayId = "B", unit = WeightUnit.LB, catalog = catalog)

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

        val rows = PlanScreenBuilder.days(program, nextDayId = "F", unit = WeightUnit.LB, catalog = catalog)

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
            unit = WeightUnit.LB,
            catalog = catalog,
        ).single()

        assertEquals(10, row.setCount)
        assertTrue(row.setCount > program.strengthDays.single().exercises.sumOf { it.targetSets })
    }

    @Test fun `a ruled strength scheme ramp counts the rows it opens with`() {
        val program = Program(listOf(ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("bb_bench", SetStructure.RAMP_TOP_BACKOFF, 3,
                rule = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH, SetStructure.STRAIGHT, 5, MovementPattern.H_PUSH)),
            ProgramExercise("pushup", targetSets = 4),
        ))))

        assertEquals(9, PlanScreenBuilder.days(program, "A", WeightUnit.LB, catalog).single().setCount)
    }

    @Test
    fun daysIncludeStandaloneCardioAndUseLetterAccentsForEveryKind() {
        val program = Program(listOf(
            day("A", "Lower", 3),
            day("C1", "Cardio", 2).copy(kind = ProgramDayKind.CARDIO),
            day("B", "Upper", 3),
            day("C2", "Cardio", 2).copy(kind = ProgramDayKind.CARDIO),
        ))
        val rows = PlanScreenBuilder.days(program, "B", WeightUnit.LB, catalog)
        assertEquals(listOf("A", "C1", "B", "C2"), rows.map { it.dayId })
        assertEquals(listOf(0, 2, 1, 2), rows.map { it.dayIndex })
        assertEquals(program.days.map { it.kind }, rows.map { it.kind })
        assertEquals(listOf("B"), rows.filter { it.isNext }.map { it.dayId })
    }

    @Test
    fun generatedPlansShowTheFourPinnedGoalsAndTheirEmphasis() {
        val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0,
            recipeJson = PlanRecipeJson.encode(WizardAnswers()))
        val state = PlanScreenBuilder.buildUiState(
            plan, ProgramGenerator.generate(WizardAnswers()), WizardAnswers(), catalog, WeightUnit.LB,
        )
        assertEquals(listOf("Barbell Back Squat", "Barbell Bench Press", "Trap-Bar Deadlift", "Incline DB Press"),
            state.goals.map { it.name })
        assertTrue(state.goals.all { it.goalLb == null && it.display == null })
        assertEquals(GoalEmphasis.BALANCED, state.emphasis)
    }

    @Test fun `a typed goal shows its weight in the chosen unit`() {
        val answers = WizardAnswers(config = LifterConfig(goalsLb = mapOf("bb_back_squat" to 200.0)))
        val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0,
            recipeJson = PlanRecipeJson.encode(answers))
        for (unit in WeightUnit.entries) {
            val state = PlanScreenBuilder.buildUiState(plan, ProgramGenerator.generate(answers), answers, catalog, unit)
            val squat = state.goals.single { it.exerciseId == "bb_back_squat" }
            assertEquals(200.0, requireNotNull(squat.goalLb), 0.0)
            assertEquals(if (unit == WeightUnit.LB) "200" else "90.72", squat.display)
            assertNull(state.goals.single { it.exerciseId == "bb_bench" }.display)
        }
    }

    @Test fun `a planned plan shows goals but no emphasis`() {
        val answers = SetupAnswers()
        val plan = PlanEntity(1, "Planned", PlanOrigin.GENERATED.name, 0, 0,
            recipeJson = SetupRecipeJson.encode(answers))
        val state = PlanScreenBuilder.buildUiState(plan, Planner.plan(answers).program, WizardAnswers(), catalog, WeightUnit.LB)
        assertTrue(state.goals.isNotEmpty())
        assertNull(state.emphasis)
    }

    @Test
    fun `goals name only lifts the plan has`() {
        val answers = WizardAnswers(equipment = Equipment.entries.toSet() - setOf(Equipment.BARBELL, Equipment.TRAP_BAR))
        val program = ProgramGenerator.generate(answers)
        val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0,
            recipeJson = PlanRecipeJson.encode(answers))
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

    @Test fun `an old wizard dumbbell plan shows its main lift goals`() {
        val answers = WizardAnswers(equipment = setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
            config = LifterConfig(level = ExperienceLevel.NOVICE, goalsLb = mapOf("goblet_squat" to 80.0)))
        val program = ProgramGenerator.generate(answers)
        val plan = PlanEntity(1, "Dumbbells", PlanOrigin.GENERATED.name, 0, 0,
            recipeJson = PlanRecipeJson.encode(answers))

        val state = PlanScreenBuilder.buildUiState(plan, program, answers, catalog, WeightUnit.LB)

        assertEquals(ProgramGenerator.mainLiftIds(answers), state.goals.map { it.exerciseId })
        assertEquals("80", state.goals.single { it.exerciseId == "goblet_squat" }.display)
    }

    @Test fun `v2 and recipe-less plans use role alone for main lift goals`() {
        val answers = WizardAnswers(equipment = setOf(Equipment.DUMBBELL))
        val program = Program(listOf(ProgramDay("A", "Lower", "", listOf(
            ProgramExercise("goblet_squat", rule = SlotRule.defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH,
                SetStructure.STRAIGHT, 5, MovementPattern.SQUAT_BILATERAL)),
            ProgramExercise("db_bench", rule = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH,
                SetStructure.STRAIGHT, 5, MovementPattern.H_PUSH)),
        ))))
        for (recipe in listOf(null, SetupRecipeJson.encode(SetupAnswers()))) {
            val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0, recipeJson = recipe)
            val state = PlanScreenBuilder.buildUiState(plan, program, answers, catalog, WeightUnit.LB)
            assertEquals(listOf("db_bench"), state.goals.map { it.exerciseId })
        }
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
