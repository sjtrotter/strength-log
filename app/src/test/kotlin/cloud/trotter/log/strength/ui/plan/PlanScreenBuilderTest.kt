package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.data.OldWizardPlan
import cloud.trotter.log.strength.data.OldWizardPlans
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.data.serialization.PlanSettings
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
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.domain.planner.RecentRace
import cloud.trotter.log.strength.domain.planner.RunExperience
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
        val state = PlanScreenBuilder.buildUiState(plan, Program(emptyList()),
            ExerciseCatalog.CODE_ONLY, WeightUnit.LB, listOf(CardioActivity.ROAD_RUN to listOf(result)))
        assertEquals(PlanGoalUi(goal, 984, false), state.goal)
        assertEquals(PlanGoalUi(goal, null, false), PlanScreenBuilder.buildUiState(
            plan, Program(emptyList()), ExerciseCatalog.CODE_ONLY, WeightUnit.LB).goal)
        assertNull(PlanScreenBuilder.buildUiState(plan.copy(goalJson = "junk"), Program(emptyList()),
            ExerciseCatalog.CODE_ONLY, WeightUnit.LB).goal)
    }

    @Test fun `a sprint goal shows its own goal row`() {
        val plan = PlanEntity(1, "Sprint", PlanOrigin.AUTHORED.name, 0, 0,
            goalJson = PlanGoalJson.encode(PlanGoal.Sprint(100)))
        val state = PlanScreenBuilder.buildUiState(plan, Program(emptyList<ProgramDay>()),
            ExerciseCatalog.CODE_ONLY, WeightUnit.LB)
        assertNull(state.goal)
        // PR 7e: the sprint row replaces the hidden sprint goal.
        assertEquals(PlanSprintGoalUi(PlanGoal.Sprint(100), null), state.sprintGoal)
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
    fun generatedPlansShowTheFourPinnedGoals() {
        val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0,
            settingsJson = oldSettings(OldWizardPlans.DEFAULT))
        val state = PlanScreenBuilder.buildUiState(
            plan, OldWizardPlans.DEFAULT.program, catalog, WeightUnit.LB,
        )
        assertEquals(listOf("Barbell Back Squat", "Barbell Bench Press", "Trap-Bar Deadlift", "Incline DB Press"),
            state.goals.map { it.name })
        assertTrue(state.goals.all { it.goalLb == null && it.display == null })
    }

    @Test fun `a typed goal shows its weight in the chosen unit`() {
        val answers = OldWizardPlans.DEFAULT.copy(goalsLb = mapOf("bb_back_squat" to 200.0))
        val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0,
            settingsJson = oldSettings(answers))
        for (unit in WeightUnit.entries) {
            val state = PlanScreenBuilder.buildUiState(plan, answers.program, catalog, unit)
            val squat = state.goals.single { it.exerciseId == "bb_back_squat" }
            assertEquals(200.0, requireNotNull(squat.goalLb), 0.0)
            assertEquals(if (unit == WeightUnit.LB) "200" else "90.72", squat.display)
            assertNull(state.goals.single { it.exerciseId == "bb_bench" }.display)
        }
    }

    @Test fun `a planned plan shows goals`() {
        val answers = SetupAnswers()
        val plan = PlanEntity(1, "Planned", PlanOrigin.GENERATED.name, 0, 0,
            recipeJson = SetupRecipeJson.encode(answers))
        val state = PlanScreenBuilder.buildUiState(plan, Planner.plan(answers).program, catalog, WeightUnit.LB)
        assertTrue(state.goals.isNotEmpty())
    }

    @Test
    fun `goals name only lifts the plan has`() {
        val replacements = mapOf("bb_back_squat" to "hack_squat", "bb_bench" to "db_bench",
            "trap_dl" to "db_rdl", "rdl" to "db_rdl")
        val answers = OldWizardPlans.DEFAULT.copy(
            equipment = Equipment.entries.toSet() - setOf(Equipment.BARBELL, Equipment.TRAP_BAR),
            mains = listOf("hack_squat", "db_bench", "db_rdl", "incline_db"),
            program = Program(OldWizardPlans.DEFAULT.program.days.map { day ->
                day.copy(exercises = day.exercises.map { slot ->
                    slot.copy(exerciseId = replacements[slot.exerciseId] ?: slot.exerciseId)
                })
            }),
        )
        val program = answers.program
        val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0,
            settingsJson = oldSettings(answers))
        val state = PlanScreenBuilder.buildUiState(plan, program, catalog, WeightUnit.LB)
        val names = state.goals.map { it.name }
        val plannedNames = program.days.flatMap { day -> day.exercises.map { ExerciseLibrary.get(it.exerciseId).name } }

        assertTrue(names.isNotEmpty())
        assertFalse("Barbell Back Squat" in names)
        assertFalse("Trap-Bar Deadlift" in names)
        assertTrue(names.all { it in plannedNames })
    }

    @Test
    fun generatedPlansWithoutLiftsHideGoals() {
        val program = Program(OldWizardPlans.CARDIO_FIRST.program.days.filter { it.exercises.isEmpty() }
            .mapIndexed { index, day -> day.copy(id = ('A' + index).toString()) })
        val plan = PlanEntity(1, "Running", PlanOrigin.GENERATED.name, 0, 0)

        val state = PlanScreenBuilder.buildUiState(plan, program, catalog, WeightUnit.LB)

        assertTrue(state.hasProgram)
        assertEquals(listOf("Easy", "Intervals", "Long"), state.days.map { it.title })
        assertTrue(state.days.all { it.liftCount == 0 && it.cardioSetCount > 0 })
        assertTrue(state.goals.isEmpty())
    }

    @Test
    fun authoredAndEmptyGeneratedPlansHideGoals() {
        val plan = PlanEntity(1, "Authored", PlanOrigin.AUTHORED.name, 0, 0)
        val authored = PlanScreenBuilder.buildUiState(
            plan, Program(listOf(day("A", "Day A"))), catalog, WeightUnit.LB,
        )
        assertTrue(authored.goals.isEmpty())
        val empty = PlanScreenBuilder.buildUiState(
            plan.copy(origin = PlanOrigin.GENERATED.name), Program(emptyList()), catalog, WeightUnit.LB,
        )
        assertTrue(empty.goals.isEmpty())
    }

    @Test fun `an old wizard dumbbell plan shows its main lift goals`() {
        val answers = OldWizardPlans.DUMBBELL_NOVICE
        val program = answers.program
        val plan = PlanEntity(1, "Dumbbells", PlanOrigin.GENERATED.name, 0, 0,
            settingsJson = oldSettings(answers))

        val state = PlanScreenBuilder.buildUiState(plan, program, catalog, WeightUnit.LB)

        assertEquals(answers.mains, state.goals.map { it.exerciseId })
        assertEquals("80", state.goals.single { it.exerciseId == "goblet_squat" }.display)
    }

    @Test fun `v2 and recipe-less plans use role alone for main lift goals`() {
        val program = Program(listOf(ProgramDay("A", "Lower", "", listOf(
            ProgramExercise("goblet_squat", rule = SlotRule.defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH,
                SetStructure.STRAIGHT, 5, MovementPattern.SQUAT_BILATERAL)),
            ProgramExercise("db_bench", rule = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH,
                SetStructure.STRAIGHT, 5, MovementPattern.H_PUSH)),
        ))))
        for (recipe in listOf(null, SetupRecipeJson.encode(SetupAnswers()))) {
            val plan = PlanEntity(1, "Generated", PlanOrigin.GENERATED.name, 0, 0, recipeJson = recipe)
            val state = PlanScreenBuilder.buildUiState(plan, program, catalog, WeightUnit.LB)
            assertEquals(listOf("db_bench"), state.goals.map { it.exerciseId })
        }
    }

    @Test fun `Run and Both plans show their stored phase`() {
        for ((branch, phase) in listOf(PlanBranch.RUN to RunPhase.LADDER, PlanBranch.BOTH to RunPhase.HALF_BUILD)) {
            val answers = SetupAnswers(branch = branch, run = RunAnswers(goal = RunGoal.HALF_MARATHON))
            val plan = PlanEntity(1, "Run", PlanOrigin.GENERATED.name, 0, 0,
                recipeJson = SetupRecipeJson.encode(answers, phase))
            val state = PlanScreenBuilder.buildUiState(plan, Program(emptyList()), catalog, WeightUnit.LB)
            assertEquals(phase, state.runPhase)
            assertNull(PlanScreenBuilder.buildUiState(plan.copy(recipeJson = SetupRecipeJson.encode(SetupAnswers())),
                Program(emptyList()), catalog, WeightUnit.LB).runPhase)
            assertNull(PlanScreenBuilder.buildUiState(plan.copy(recipeJson = SetupRecipeJson.withPhase(plan.recipeJson!!, null)),
                Program(emptyList()), catalog, WeightUnit.LB).runPhase)
            assertNull(PlanScreenBuilder.buildUiState(plan.copy(recipeJson = null, goalJson = PlanGoalJson.encode(
                PlanGoal.Sprint(100, 2000))), Program(emptyList()), catalog, WeightUnit.LB).runPhase)
        }
    }

    @Test fun `a dated half shows Taper twelve days before the goal`() {
        val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 21_100, 7200, 20_012)
        // PR 7b: Taper requires an eligible run recipe, like the phase line.
        for (branch in listOf(PlanBranch.RUN, PlanBranch.BOTH)) {
            val plan = PlanEntity(1, "Run", PlanOrigin.GENERATED.name, 0, 0, goalJson = PlanGoalJson.encode(goal),
                recipeJson = SetupRecipeJson.encode(SetupAnswers(branch = branch)))
            assertTrue(PlanScreenBuilder.buildUiState(plan, Program(emptyList()), catalog, WeightUnit.LB,
                todayEpochDay = 20_000).taper)
            assertFalse(PlanScreenBuilder.buildUiState(plan, Program(emptyList()), catalog, WeightUnit.LB,
                todayEpochDay = 19_990).taper)
        }
    }

    @Test fun `a Lift recipe with a dated Timed goal hides phase and Taper`() {
        val answers = SetupAnswers(branch = PlanBranch.LIFT)
        val plan = PlanEntity(1, "Lift", PlanOrigin.GENERATED.name, 0, 0,
            recipeJson = SetupRecipeJson.encode(answers),
            goalJson = PlanGoalJson.encode(PlanGoal.Timed(CardioActivity.ROAD_RUN, 21_100, 7200, 20_012)))
        val state = PlanScreenBuilder.buildUiState(plan, Program(emptyList()), catalog, WeightUnit.LB,
            todayEpochDay = 20_000)
        assertNull(state.runPhase)
        assertFalse(state.taper)
    }

    @Test fun `a 10K goal predicts from a recent 5K and an ultra does not`() {
        val answers = SetupAnswers(branch = PlanBranch.RUN,
            run = RunAnswers(goal = RunGoal.FIVE_TO_TEN_K, experience = RunExperience.SOME, recent = RecentRace(5000, 1500)))
        val plan = PlanEntity(1, "Run", PlanOrigin.GENERATED.name, 0, 0,
            recipeJson = SetupRecipeJson.encode(answers, RunPhase.BASE),
            goalJson = PlanGoalJson.encode(PlanGoal.Timed(CardioActivity.ROAD_RUN, 10_000, 3300)))
        val goal = PlanScreenBuilder.buildUiState(plan, Program(emptyList()), catalog, WeightUnit.LB).goal!!
        assertEquals(3127, goal.predictedSeconds)
        assertEquals(RecentRace(5000, 1500), goal.predictedFrom)
        val ultra = PlanScreenBuilder.buildUiState(plan.copy(goalJson = PlanGoalJson.encode(
            PlanGoal.Timed(CardioActivity.ROAD_RUN, 50_000, null))), Program(emptyList()), catalog, WeightUnit.LB).goal!!
        assertNull(ultra.predictedSeconds)
        assertNull(ultra.predictedFrom)
    }

    private fun day(id: String, title: String, vararg rounds: Int) = ProgramDay(
        id = id,
        title = title,
        emphasisLine = "",
        exercises = rounds.mapIndexed { index, count ->
            ProgramExercise(exerciseId = "exercise-$id-$index", targetSets = count)
        },
    )
    private fun oldSettings(answers: OldWizardPlan) = PlanSettingsJson.encode(PlanSettings(
        equipment = answers.equipment, goalsLb = answers.goalsLb,
        legacyMains = answers.mains.toSet()))
    @Test fun `sprint rows count the planned block after two steps and mark rest before sprint`() {
        val program = Planner.plan(SetupAnswers(branch = PlanBranch.SPRINT, days = 5)).program.let { program ->
            program.copy(days = program.days.map { day ->
                if (day.id == "A") day.copy(cardioRule = requireNotNull(day.cardioRule).copy(intro = 0, steps = 2)) else day
            })
        }
        val rows = PlanScreenBuilder.days(program, "A", WeightUnit.LB, catalog)
        assertEquals(listOf("C"), rows.filter { it.beforeSprint }.map { it.dayId })
        assertEquals(8, requireNotNull(program.days.first().cardioBlock).sets.size)
        assertEquals(12, rows.first().cardioSetCount)
        assertEquals(CardioActivity.TRACK_RUN, rows.first().cardioActivity)
    }

}
