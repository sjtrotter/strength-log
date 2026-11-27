package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.domain.planner.LiftTest
import cloud.trotter.log.strength.domain.planner.TestAnswer
import cloud.trotter.log.strength.data.serialization.TestGoalJson
import cloud.trotter.log.strength.domain.model.TestGoal
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.ui.log.testResultFixture
import cloud.trotter.log.strength.data.OldWizardPlans
import cloud.trotter.log.strength.data.insertRuledOldWizardPlan
import cloud.trotter.log.strength.data.runLaunchFixups
import cloud.trotter.log.strength.domain.model.ProgramExercise
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.Taper
import kotlinx.coroutines.flow.MutableStateFlow
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.SprintAttempt
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.mapping.toEntity
import android.os.Parcel
import kotlinx.parcelize.parcelableCreator
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.day.CardioBlockDraft
import cloud.trotter.log.strength.ui.day.CardioTemplate
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanViewModelTest {

    @Test fun `the test draft parses with the event rows timed flag and keeps stored refs`() {
        val ui = requireNotNull(testGoalPlanFixture().testGoal)
        val draft = TestGoalDraft(mapOf("aft_hrp" to "45", "aft_plank" to "3:30"), 21000)
        val stored = ui.goal.copy(events = ui.goal.events.reversed())
        val parsed = requireNotNull(draft.goal(stored, ui.rows))
        assertEquals(stored.events.map { it.ref }, parsed.events.map { it.ref })
        assertEquals(listOf(210, 45), parsed.events.map { it.required })
        assertEquals(21000L, parsed.byEpochDay)
        assertNull(draft.goal(stored, ui.rows.map { it.copy(timed = !it.timed) }))
        assertNull(draft.goal(stored, ui.rows.take(1)))
    }

    @Test fun `saving the test goal writes the plan column and removing it clears it`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Test prep")
        val other = repo.plans.createAuthoredPlan("Other", "Day")
        val goal = TestGoal(listOf(TestEventGoal(TestRef("aft_hrp", 1, 120), 40), TestEventGoal(TestRef("aft_plank", 1), 220)))
        repo.plans.setTestGoal(viewed, goal)
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString()))
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.openTestGoal()
        advanceUntilIdle()
        assertEquals(TestGoalDraft(mapOf("aft_hrp" to "40", "aft_plank" to "3:40")), vm.uiState.value.testGoalDraft)
        val draft = TestGoalDraft(mapOf("aft_hrp" to "45", "aft_plank" to "3:30"), 21000)
        vm.changeTestGoalDraft(draft)
        vm.saveTestGoal()
        advanceUntilIdle()
        assertEquals(goal.copy(events = goal.events.mapIndexed { index, event -> event.copy(required = if (index == 0) 45 else 210) },
            byEpochDay = 21000), TestGoalJson.decode(db.planDao().plan(viewed)!!.testGoalJson))
        assertNull(handle.get<TestGoalDraft>("plan_test_goal_draft"))
        assertNull(db.planDao().plan(other)!!.testGoalJson)
        vm.openTestGoal()
        vm.removeTestGoal()
        advanceUntilIdle()
        assertNull(db.planDao().plan(viewed)!!.testGoalJson)
        assertNull(vm.uiState.value.testGoalDraft)
        assertNull(vm.uiState.value.testGoal)
    }

    @Test fun `both goal editors save the shared fitness test date`() = runTest(dispatcher) {
        val original = SetupAnswers(branch = PlanBranch.BOTH, run = RunAnswers(goal = RunGoal.FITNESS_TEST, goalSeconds = 960),
            tests = listOf(TestAnswer(TestEventGoal(TestRef("aft_hrp", 1, 120), 40))))
        val id = repo.plans.createPlannedPlan("Test", original)
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to id.toString())))
        advanceUntilIdle()
        vm.openTestGoal()
        vm.changeTestGoalDraft(TestGoalDraft(mapOf("aft_hrp" to "45"), 21000))
        vm.saveTestGoal(); advanceUntilIdle()
        assertEquals(21000L, PlanGoalJson.decode(db.planDao().plan(id)!!.goalJson)?.byEpochDay)
        assertEquals(21000L, TestGoalJson.decode(db.planDao().plan(id)!!.testGoalJson)?.byEpochDay)
        vm.openGoal(); advanceUntilIdle()
        vm.changeGoalDraft(requireNotNull(vm.uiState.value.goalDraft).copy(byEpochDay = 21100))
        vm.saveGoal(); advanceUntilIdle()
        assertEquals(21100L, PlanGoalJson.decode(db.planDao().plan(id)!!.goalJson)?.byEpochDay)
        assertEquals(21100L, TestGoalJson.decode(db.planDao().plan(id)!!.testGoalJson)?.byEpochDay)
    }

    @Test fun `a lift only test card edits its required weight through plan settings`() = runTest(dispatcher) {
        val original = SetupAnswers(lifts = listOf(LiftTest("incline_db", 5)))
        val id = repo.plans.createPlannedPlan("Test", original, mapOf("incline_db" to 100.0))
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to id.toString())))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.goals.any { it.exerciseId == "incline_db" })
        vm.openGoal("incline_db"); advanceUntilIdle()
        assertEquals(100.0, vm.uiState.value.goalDialog?.draftLb)
        vm.setGoalDraft(110.0); vm.saveGoal(); advanceUntilIdle()
        assertEquals(110.0, vm.uiState.value.testLifts.single().goalLb)
        assertEquals(5, vm.uiState.value.testLifts.single().reps)
    }

    @Test fun `the test draft survives parcel recreation and rejects invalid values without closing`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Test prep")
        val goal = TestGoal(listOf(TestEventGoal(TestRef("aft_hrp", 1, 120), 40), TestEventGoal(TestRef("aft_plank", 1), 220)))
        repo.plans.setTestGoal(viewed, goal)
        val draft = TestGoalDraft(mapOf("aft_hrp" to "45", "aft_plank" to "3:30"), 21000)
        val parcel = Parcel.obtain()
        val restored = try {
            draft.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            parcelableCreator<TestGoalDraft>().createFromParcel(parcel)
        } finally { parcel.recycle() }
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString(), "plan_test_goal_draft" to restored))
        val vm = viewModel(handle)
        advanceUntilIdle()
        assertEquals(draft, vm.uiState.value.testGoalDraft)
        for (invalid in listOf("", "0", "3601", "abc", "1.5")) {
            val bad = draft.copy(required = draft.required + ("aft_hrp" to invalid))
            vm.changeTestGoalDraft(bad)
            vm.saveTestGoal()
            advanceUntilIdle()
            assertEquals(bad, vm.uiState.value.testGoalDraft)
            assertEquals(goal, TestGoalJson.decode(db.planDao().plan(viewed)!!.testGoalJson))
        }
        for (invalid in listOf("", "0:00", "60:01", "3:60")) {
            val bad = draft.copy(required = draft.required + ("aft_plank" to invalid))
            vm.changeTestGoalDraft(bad)
            vm.saveTestGoal()
            advanceUntilIdle()
            assertEquals(bad, vm.uiState.value.testGoalDraft)
            assertEquals(goal, TestGoalJson.decode(db.planDao().plan(viewed)!!.testGoalJson))
        }
        vm.changeTestGoalDraft(draft.copy(byEpochDay = Long.MAX_VALUE))
        vm.saveTestGoal()
        advanceUntilIdle()
        assertEquals(goal, TestGoalJson.decode(db.planDao().plan(viewed)!!.testGoalJson))
        vm.changeTestGoalDraft(draft.copy(required = mapOf("aft_hrp" to "3600", "aft_plank" to "60:00")))
        vm.saveTestGoal()
        advanceUntilIdle()
        assertEquals(listOf(3600, 3600), TestGoalJson.decode(db.planDao().plan(viewed)!!.testGoalJson)!!.events.map { it.required })
        vm.openTestGoal()
        vm.dismissTestGoal()
        advanceUntilIdle()
        assertNull(vm.uiState.value.testGoalDraft)
    }

    @Test fun `new test results update the goal card while the plan remains open`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Test prep")
        repo.plans.setTestGoal(viewed, TestGoal(listOf(TestEventGoal(TestRef("aft_hrp", 1, 120), 40))))
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        assertNull(vm.uiState.value.testGoal!!.rows.single().best)
        repo.history.logTestResult(testResultFixture(value = 40))
        advanceUntilIdle()
        assertEquals(40, vm.uiState.value.testGoal!!.rows.single().best)
        assertEquals(true, vm.uiState.value.testGoal!!.rows.single().met)
    }

    @Test fun `opening and saving a sprint goal uses its own draft and preserves attempts`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Sprint")
        val stored = PlanGoal.Sprint(100, attempts = listOf(SprintAttempt(123L,
            SprintTime(1284, TimingMethod.PHONE, StartProtocol.THREE_POINT))))
        db.planDao().updateGoal(viewed, PlanGoalJson.encode(stored))
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString()))
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.openGoal()
        advanceUntilIdle()
        assertEquals(SprintGoalDraft(), handle.get<SprintGoalDraft>("plan_sprint_goal_draft"))
        assertNull(handle.get<PlanGoalDraft>("plan_goal_draft"))
        vm.changeSprintGoalDraft(SprintGoalDraft("12.50", 21000))
        vm.saveSprintGoal()
        advanceUntilIdle()
        assertEquals(stored.copy(targetCentis = 1250, byEpochDay = 21000),
            PlanGoalJson.decode(db.planDao().plan(viewed)!!.goalJson))
        assertNull(handle.get<SprintGoalDraft>("plan_sprint_goal_draft"))
        assertNull(handle.get<PlanGoalDraft>("plan_goal_draft"))
    }

    @Test fun `a generic goal save never replaces a stored sprint goal`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Sprint")
        val stored = PlanGoal.Sprint(100)
        db.planDao().updateGoal(viewed, PlanGoalJson.encode(stored))
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString(), "plan_goal_draft" to PlanGoalDraft()))
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.saveGoal()
        advanceUntilIdle()
        assertEquals(stored, PlanGoalJson.decode(db.planDao().plan(viewed)!!.goalJson))
    }

    @Test fun `offer switches update only the viewed plan`() = runTest(dispatcher) {
        val preferred = repo.plans.createAuthoredPlan("Preferred", "Day")
        repo.plans.publishCreatedAuthoredPlan(preferred)
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.offerStrengthIncreases)
        assertEquals(false, vm.uiState.value.offerCardioIncreases)
        vm.setOfferStrengthIncreases(false)
        vm.setOfferCardioIncreases(true)
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.offerStrengthIncreases)
        assertEquals(true, vm.uiState.value.offerCardioIncreases)
        assertEquals(false, db.planDao().plan(viewed)!!.offerStrengthIncreases)
        assertEquals(true, db.planDao().plan(viewed)!!.offerCardioIncreases)
        assertEquals(true, db.planDao().plan(preferred)!!.offerStrengthIncreases)
        assertEquals(false, db.planDao().plan(preferred)!!.offerCardioIncreases)
    }

    @Test fun `save a timed goal from the draft and preserve it across process death`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString()))
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.openGoal()
        advanceUntilIdle()
        assertEquals(PlanGoalDraft(), vm.uiState.value.goalDraft)
        val draft = PlanGoalDraft(activity = "TRAIL_RUN", metres = 5000, underSeconds = 1500, byEpochDay = 21000)
        vm.changeGoalDraft(draft)
        val parcel = Parcel.obtain()
        val restoredDraft = try {
            handle.get<PlanGoalDraft>("plan_goal_draft")!!.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            parcelableCreator<PlanGoalDraft>().createFromParcel(parcel)
        } finally { parcel.recycle() }
        val restoredHandle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString(), "plan_goal_draft" to restoredDraft))
        vm.viewModelScope.cancel()
        val restored = viewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals(draft, restored.uiState.value.goalDraft)
        restored.saveGoal()
        advanceUntilIdle()
        assertEquals(draft.goal(), PlanGoalJson.decode(db.planDao().plan(viewed)!!.goalJson))
        assertNull(restored.uiState.value.goalDraft)
        assertNull(restoredHandle.get<PlanGoalDraft>("plan_goal_draft"))
        restored.openGoal()
        advanceUntilIdle()
        assertEquals(draft, restored.uiState.value.goalDraft)
        restored.removeGoal()
        advanceUntilIdle()
        assertNull(restored.uiState.value.goal)
    }

    @Test fun `invalid cardio goal drafts cannot save and cancel leaves storage alone`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        vm.openGoal()
        // PR 7c: blank time saves Just finish; a negative time remains invalid
        for (invalid in listOf(PlanGoalDraft(metres = 0), PlanGoalDraft(underSeconds = -1), PlanGoalDraft(underSeconds = -2),
            PlanGoalDraft(activity = "UNKNOWN"), PlanGoalDraft(timed = false, protocol = " "))) {
            vm.changeGoalDraft(invalid)
            vm.saveGoal()
            advanceUntilIdle()
            assertEquals(invalid, vm.uiState.value.goalDraft)
            assertNull(db.planDao().plan(viewed)!!.goalJson)
        }
        vm.dismissGoal()
        advanceUntilIdle()
        assertNull(vm.uiState.value.goalDraft)
    }

    @Test fun `timed goal drafts reject times below the activity floor`() {
        val marathon = PlanGoalDraft(metres = 42_200, underSeconds = 210)
        assertNull(marathon.goal())
        assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 42_200, 4220), marathon.copy(underSeconds = 4220).goal())
        assertNull(marathon.copy(underSeconds = 4219).goal())
        val ride = marathon.copy(activity = CardioActivity.OUTDOOR_RIDE.name, metres = 40_000, underSeconds = 3600)
        assertEquals(PlanGoal.Timed(CardioActivity.OUTDOOR_RIDE, 40_000, 3600), ride.goal())
        assertEquals(PlanGoal.Timed(CardioActivity.OUTDOOR_RIDE, 40_000, 1), ride.copy(underSeconds = 1).goal())
    }

    @Test fun `changing goal distance or activity persists an invalid time marker`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString()))
        val vm = viewModel(handle)
        advanceUntilIdle()
        val short = PlanGoalDraft(metres = 800, underSeconds = 101)
        vm.changeGoalDraft(short)
        vm.changeGoalDraft(short.copy(metres = 1500))
        advanceUntilIdle()
        assertEquals(INVALID_TIME, vm.uiState.value.goalDraft!!.underSeconds)
        assertEquals(INVALID_TIME, handle.get<PlanGoalDraft>("plan_goal_draft")!!.underSeconds)
        vm.changeGoalDraft(vm.uiState.value.goalDraft!!.copy(metres = 800))
        advanceUntilIdle()
        assertEquals(INVALID_TIME, vm.uiState.value.goalDraft!!.underSeconds)
        val ride = PlanGoalDraft(activity = CardioActivity.OUTDOOR_RIDE.name, metres = 40_000, underSeconds = 3600)
        vm.changeGoalDraft(ride)
        advanceUntilIdle()
        assertEquals(ride, vm.uiState.value.goalDraft)
        vm.changeGoalDraft(ride.copy(activity = CardioActivity.ROAD_RUN.name))
        vm.saveGoal()
        advanceUntilIdle()
        assertEquals(INVALID_TIME, vm.uiState.value.goalDraft!!.underSeconds)
        assertNull(db.planDao().plan(viewed)!!.goalJson)
        vm.changeGoalDraft(ride)
        vm.saveGoal()
        advanceUntilIdle()
        assertEquals(ride.goal(), PlanGoalJson.decode(db.planDao().plan(viewed)!!.goalJson))
    }

    @Test fun `a blank time saves Just finish with its date`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        vm.openGoal()
        vm.changeGoalDraft(PlanGoalDraft(metres = 42195, underSeconds = 0, byEpochDay = 21000))
        vm.saveGoal()
        advanceUntilIdle()
        assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 42195, null, 21000),
            PlanGoalJson.decode(db.planDao().plan(viewed)!!.goalJson))
        assertNull(vm.uiState.value.goalDraft)
    }

    @Test fun `out of range dates cannot save either cardio goal draft`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val saved = PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960)
        repo.plans.setGoal(viewed, saved)
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        vm.openGoal()
        for (day in listOf(LocalDate.of(1999, 12, 31).toEpochDay(), LocalDate.of(2101, 1, 1).toEpochDay(),
            LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay(), Long.MIN_VALUE, Long.MAX_VALUE)) {
            for (timed in listOf(true, false)) {
                val draft = PlanGoalDraft(timed = timed, protocol = "PACER", byEpochDay = day)
                vm.changeGoalDraft(draft)
                vm.saveGoal()
                advanceUntilIdle()
                assertEquals(draft, vm.uiState.value.goalDraft)
                assertEquals(saved, PlanGoalJson.decode(db.planDao().plan(viewed)!!.goalJson))
            }
        }
    }

    @Test fun `shuttle entry survives recreation and logging updates best`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString()))
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.openGoal()
        vm.changeGoalDraft(PlanGoalDraft(timed = false, protocol = " PACER ", targetShuttles = 52))
        vm.saveGoal()
        advanceUntilIdle()
        assertEquals(PlanGoal.Shuttle("PACER", 52), vm.uiState.value.goal!!.goal)
        vm.openShuttleEntry()
        vm.saveShuttleEntry()
        advanceUntilIdle()
        assertNull(vm.uiState.value.goal!!.best)
        vm.changeShuttleEntry("44")
        vm.changeShuttleEntry("4444")
        vm.changeShuttleEntry("4x")
        assertEquals("44", handle.get<String>("plan_shuttle_entry"))
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        vm.viewModelScope.cancel()
        val restored = viewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals("44", restored.uiState.value.shuttleEntry)
        restored.saveShuttleEntry()
        advanceUntilIdle()
        assertEquals(44, restored.uiState.value.goal!!.best)
        assertEquals(false, restored.uiState.value.goal!!.met)
        assertNull(restored.uiState.value.shuttleEntry)
    }

    @Test fun `timed goal observes cardio attempts from every plan and later history edits`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val other = repo.plans.createAuthoredPlan("Other", "Run")
        val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960)
        repo.plans.setGoal(viewed, goal)
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        assertNull(vm.uiState.value.goal!!.best)
        val session = CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Road run",
            startedAt = 1000, completedAt = 985000, seconds = 984, stepsCompleted = 1, planId = other, activity = "ROAD_RUN")
        val id = db.cardioSessionDao().insert(session)
        db.cardioSetResultDao().insertAll(listOf(CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(3219)),
            CardioSetStatus.FINISHED, 984).toEntity(id, 0)))
        advanceUntilIdle()
        assertEquals(PlanGoalUi(goal, 984, false), vm.uiState.value.goal)
        db.cardioSessionDao().update(session.copy(id = id, activity = "TRACK_RUN"))
        advanceUntilIdle()
        assertEquals(PlanGoalUi(goal, null, false), vm.uiState.value.goal)
    }

    @Test fun `the taper line follows the day as it turns`() = runTest(dispatcher) {
        val eventDay = LocalDate.of(2026, 12, 1)
        val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 5000, 1500, eventDay.toEpochDay())
        val outside = (1L..30L).first { Taper.factor(goal, eventDay.minusDays(it).toEpochDay()) == null }
        val dayOne = eventDay.minusDays(outside)
        val dayTwo = dayOne.plusDays(1)
        assertNull(Taper.factor(goal, dayOne.toEpochDay()))
        assertEquals(0.5, Taper.factor(goal, dayTwo.toEpochDay()))
        val today = MutableStateFlow(dayOne)
        // ladder-date: a New plan starts on the ladder, which never shows Taper
        val viewed = repo.plans.createPlannedPlan("Run", SetupAnswers(branch = PlanBranch.RUN,
            run = RunAnswers(goal = RunGoal.FIVE_TO_TEN_K, experience = RunExperience.SOME)))
        repo.plans.setGoal(viewed, goal)
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString()))
        val vm = PlanViewModel(repo, handle, context, settings = settings, plans = repo.plans,
            catalog = repo.catalog, today = today)
        viewModels += vm
        vm.viewModelScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.loading)
        assertEquals(false, vm.uiState.value.taper)
        today.value = dayTwo
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.taper)
    }

    @Test fun `a distance change resets a time below the new floor once`() = runTest(dispatcher) {
        val viewed = repo.plans.createAuthoredPlan("Viewed", "Run")
        val short = PlanGoalDraft(metres = 800, underSeconds = 101)
        val handle = SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString(), "plan_goal_draft" to short))
        val vm = viewModel(handle)
        vm.changeGoalDraft(short.copy(metres = 1500))
        val cleared = handle.get<PlanGoalDraft>("plan_goal_draft")!!
        assertEquals(INVALID_TIME, cleared.underSeconds)
        vm.changeGoalDraft(cleared)
        assertEquals(cleared, handle.get<PlanGoalDraft>("plan_goal_draft"))
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var context: Context
    private lateinit var storeScope: CoroutineScope
    private val viewModels = mutableListOf<PlanViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("plan-days-vm", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        storeScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun viewModel(handle: SavedStateHandle = SavedStateHandle()): PlanViewModel {
        if (!handle.contains(Routes.PLAN_ID_ARG)) handle[Routes.PLAN_ID_ARG] = repo.plans.selectedPlanIdFlow.first()!!.toString()
        return PlanViewModel(repo, handle, context, settings = settings, plans = repo.plans, catalog = repo.catalog).also { vm ->
            viewModels += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }
    }

    @Test
    fun `goal edits and unit changes update the viewed generated plan`() = runTest(dispatcher) {
        val preferred = db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings, "Preferred").also { db.runLaunchFixups(settings) }
        repo.plans.publishPlannedPlan(preferred)
        val tapped = db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings, "Viewed").also { db.runLaunchFixups(settings) }
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to tapped.toString())))
        advanceUntilIdle()
        assertNull(vm.uiState.value.goals.first().display)
        assertEquals(preferred, repo.plans.selectedPlanIdFlow.first())
        repo.plans.setPlanGoal(tapped, "bb_back_squat", 200.0)
        advanceUntilIdle()
        val pounds = vm.uiState.value.goals
        settings.setUnit(WeightUnit.KG)
        advanceUntilIdle()
        assertEquals(false, pounds == vm.uiState.value.goals)
        assertEquals(tapped, vm.uiState.value.planId)
    }

    @Test
    fun `saving a goal writes it to the plan`() = runTest(dispatcher) {
        val preferred = db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings, "Preferred").also { db.runLaunchFixups(settings) }
        repo.plans.publishPlannedPlan(preferred)
        val viewed = db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings, "Viewed").also { db.runLaunchFixups(settings) }
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        vm.openGoal("bb_back_squat")
        advanceUntilIdle()
        assertNull(vm.uiState.value.goalDialog!!.draftLb)
        assertEquals(false, vm.uiState.value.goalDialog!!.hasGoal)
        vm.setGoalDraft(300.0)
        vm.saveGoal()
        advanceUntilIdle()

        assertEquals(mapOf("bb_back_squat" to 300.0), repo.plans.planSettingsFor(viewed).first().goalsLb)
        assertEquals(emptyMap<String, Double>(), repo.plans.planSettingsFor(preferred).first().goalsLb)
        assertNull(vm.uiState.value.goalDialog)
        assertEquals("300", vm.uiState.value.goals.first().display)
        vm.openGoal("bb_back_squat")
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.goalDialog!!.hasGoal)
        vm.removeLiftGoal()
        advanceUntilIdle()
        assertEquals(emptyMap<String, Double>(), repo.plans.planSettingsFor(viewed).first().goalsLb)
        assertNull(vm.uiState.value.goalDialog)
        assertNull(vm.uiState.value.goals.first().display)
    }

    @Test
    fun `offer switches and goal edits preserve each other on the viewed generated plan`() = runTest(dispatcher) {
        val preferred = db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings, "Preferred").also { db.runLaunchFixups(settings) }
        repo.plans.publishPlannedPlan(preferred)
        val viewed = db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings, "Viewed").also { db.runLaunchFixups(settings) }
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to viewed.toString())))
        advanceUntilIdle()
        vm.openGoal("bb_back_squat")
        vm.setGoalDraft(200.0)
        advanceUntilIdle()
        val dialog = vm.uiState.value.goalDialog

        vm.setOfferStrengthIncreases(false)
        vm.setOfferCardioIncreases(true)
        advanceUntilIdle()

        assertEquals(dialog, vm.uiState.value.goalDialog)
        assertEquals(false, vm.uiState.value.offerStrengthIncreases)
        assertEquals(true, vm.uiState.value.offerCardioIncreases)
        vm.saveGoal()
        advanceUntilIdle()
        assertNull(vm.uiState.value.goalDialog)
        assertEquals("200", vm.uiState.value.goals.first().display)
        assertEquals(mapOf("bb_back_squat" to 200.0), repo.plans.planSettingsFor(viewed).first().goalsLb)
        assertEquals(false, db.planDao().plan(viewed)!!.offerStrengthIncreases)
        assertEquals(true, db.planDao().plan(viewed)!!.offerCardioIncreases)
        assertEquals(true, db.planDao().plan(preferred)!!.offerStrengthIncreases)
        assertEquals(false, db.planDao().plan(preferred)!!.offerCardioIncreases)
        assertEquals(emptyMap<String, Double>(), repo.plans.planSettingsFor(preferred).first().goalsLb)
    }

    @Test
    fun `goal dialog and its draft survive recreation and keep the viewed plan`() = runTest(dispatcher) {
        val plan = db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings, "Goals").also { db.runLaunchFixups(settings) }
        repo.plans.publishPlannedPlan(plan)
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.openGoal("bb_back_squat")
        vm.setGoalDraft(200.0)
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        vm.viewModelScope.cancel()
        val other = repo.plans.createAuthoredPlan("Other", "Day A")
        repo.plans.publishCreatedAuthoredPlan(other)
        val restored = viewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals(PlanGoalDialog("bb_back_squat", "Barbell Back Squat", false, 200.0, false),
            restored.uiState.value.goalDialog)
        restored.saveGoal()
        advanceUntilIdle()
        assertEquals(mapOf("bb_back_squat" to 200.0), repo.plans.planSettingsFor(plan).first().goalsLb)
        assertEquals(emptyMap<String, Double>(), repo.plans.planSettingsFor(other).first().goalsLb)
        assertNull(restored.uiState.value.goalDialog)
        assertNull(restoredHandle.get<String>("plan_goal_exercise"))
        assertNull(restoredHandle.get<Double>("plan_goal_draft_lb"))
    }

    @Test
    fun `invalid goal drafts cannot save and a missing goal cannot open`() = runTest(dispatcher) {
        val plan = db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings, "Goals").also { db.runLaunchFixups(settings) }
        repo.plans.publishPlannedPlan(plan)
        val vm = viewModel()
        advanceUntilIdle()
        vm.openGoal("missing")
        advanceUntilIdle()
        assertNull(vm.uiState.value.goalDialog)
        vm.openGoal("bb_back_squat")
        for (invalid in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY,
            PlanRecipeJson.MAX_GOAL_LB + 0.1, 2500.0, WeightUnit.KG.toLb(907.2))) {
            vm.setGoalDraft(invalid)
            vm.saveGoal()
            advanceUntilIdle()
            assertEquals("bb_back_squat", vm.uiState.value.goalDialog!!.exerciseId)
            assertEquals(emptyMap<String, Double>(), repo.plans.planSettingsFor(plan).first().goalsLb)
        }
        vm.dismissGoal()
        advanceUntilIdle()
        assertNull(vm.uiState.value.goalDialog)
        val restored = viewModel(SavedStateHandle(mapOf(
            Routes.PLAN_ID_ARG to plan.toString(), "plan_goal_exercise" to "missing", "plan_goal_draft_lb" to 200.0,
        )))
        advanceUntilIdle()
        assertNull(restored.uiState.value.goalDialog)
    }

    @Test
    fun `saving the goal ceiling accepts canonical pounds in either display unit`() = runTest(dispatcher) {
        val plan = db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings, "Goals").also { db.runLaunchFixups(settings) }
        repo.plans.publishPlannedPlan(plan)
        val vm = viewModel()
        for (unit in WeightUnit.entries) {
            settings.setUnit(unit)
            advanceUntilIdle()
            vm.openGoal("bb_back_squat")
            vm.setGoalDraft(PlanRecipeJson.MAX_GOAL_LB)
            vm.saveGoal()
            advanceUntilIdle()
            assertEquals(mapOf("bb_back_squat" to PlanRecipeJson.MAX_GOAL_LB),
                repo.plans.planSettingsFor(plan).first().goalsLb)
            assertNull(vm.uiState.value.goalDialog)
        }
    }

    @Test
    fun `tapped plan stays visible and all day actions target it while preferred changes`() = runTest(dispatcher) {
        val preferred = repo.plans.createAuthoredPlan("Preferred", "Preferred day")
        repo.plans.publishCreatedAuthoredPlan(preferred)
        val tapped = repo.plans.createAuthoredPlan("Physio", "Rehab")
        val vm = viewModel(SavedStateHandle(mapOf(Routes.PLAN_ID_ARG to tapped.toString())))
        advanceUntilIdle()
        assertEquals("Physio", vm.uiState.value.name)
        assertEquals(listOf("Rehab"), vm.uiState.value.days.map { it.title })
        vm.addDay()
        advanceUntilIdle()
        assertEquals(2, db.programDao().daysForPlan(tapped).size)
        assertEquals(1, db.programDao().daysForPlan(preferred).size)
        repo.plans.selectPlan(tapped)
        repo.plans.selectPlan(preferred)
        advanceUntilIdle()
        assertEquals(tapped, vm.uiState.value.planId)
    }

    @Test
    fun `added cardio only day exposes its activity and set count immediately`() = runTest(dispatcher) {
        val plan = repo.plans.createAuthoredPlan("Running", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        val vm = viewModel()
        advanceUntilIdle()
        vm.addDay()
        advanceUntilIdle()
        val block = CardioBlockDraft.new(plan, "B").startFrom(CardioTemplate.INTERVALS).block()
        repo.setCardioBlock(plan, "B", block)
        advanceUntilIdle()
        val row = vm.uiState.value.days.single { it.dayId == "B" }
        assertEquals(0, row.liftCount)
        assertEquals(block.activity, row.cardioActivity)
        assertEquals(11, row.cardioSetCount)
    }

    @Test
    fun `authored days add rename and remove immediately with a seven day cap`() = runTest(dispatcher) {
        val plan = repo.plans.createAuthoredPlan("Prep", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        val vm = viewModel()
        advanceUntilIdle()
        repeat(8) { vm.addDay() }
        advanceUntilIdle()
        assertEquals(('A'..'G').map { it.toString() }, vm.uiState.value.days.map { it.dayId })
        vm.renameDay("B")
        vm.setDayTitleDraft("  Test practice  ")
        vm.saveDayDialog()
        advanceUntilIdle()
        assertEquals("Test practice", db.programDao().day(plan, "B")!!.title)
        vm.removeDay("B")
        advanceUntilIdle()
        assertEquals("remove", vm.uiState.value.dialog)
        vm.saveDayDialog()
        advanceUntilIdle()
        assertNull(db.programDao().day(plan, "B"))
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    fun `day dialog survives recreation and writes to its original plan after selection changes`() = runTest(dispatcher) {
        val plan = repo.plans.createAuthoredPlan("Physio", "Day A")
        repo.plans.publishCreatedAuthoredPlan(plan)
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        advanceUntilIdle()
        vm.renameDay("A")
        vm.setDayTitleDraft("Daily rehab")
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        vm.viewModelScope.cancel()
        val other = repo.plans.createAuthoredPlan("Other", "Day A")
        repo.plans.publishCreatedAuthoredPlan(other)
        val restored = viewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals("rename", restored.uiState.value.dialog)
        assertEquals("Daily rehab", restored.uiState.value.dayTitleDraft)
        restored.saveDayDialog()
        advanceUntilIdle()
        assertEquals("Daily rehab", db.programDao().day(plan, "A")!!.title)
        assertEquals("Day A", db.programDao().day(other, "A")!!.title)
    }

    @Test
    fun `generated plan adds a seventh strength day alongside standalone cardio`() = runTest(dispatcher) {
        repo.replaceProgram(Program(('A'..'F').map {
            ProgramDay(it.toString(), "Full Body", "", listOf(ProgramExercise("plank")))
        } + listOf("C1", "C2").map {
            ProgramDay(it, "Cardio + Core", "", emptyList(), kind = ProgramDayKind.CARDIO)
        }))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(8, vm.uiState.value.days.size)
        vm.addDay()
        advanceUntilIdle()
        assertEquals(('A'..'G').map { it.toString() },
            vm.uiState.value.days.filter { it.kind == ProgramDayKind.STRENGTH }.map { it.dayId })
        assertEquals(9, vm.uiState.value.days.size)
        assertEquals(listOf("C1", "C2"), repo.plans.programFor(plan).first().cardioDays.map { it.id })
        vm.addDay()
        advanceUntilIdle()
        assertEquals(9, db.programDao().daysForPlan(plan).size)
    }

    @Test
    fun `generated days add rename and remove immediately`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Generated", "", emptyList()))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val vm = viewModel()
        advanceUntilIdle()
        vm.addDay()
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.uiState.value.days.map { it.dayId })
        vm.renameDay("A")
        vm.setDayTitleDraft("Renamed")
        vm.saveDayDialog()
        advanceUntilIdle()
        assertEquals("Renamed", db.programDao().day(plan, "A")!!.title)
        vm.removeDay("B")
        advanceUntilIdle()
        assertEquals("remove", vm.uiState.value.dialog)
        vm.saveDayDialog()
        advanceUntilIdle()
        assertNull(vm.uiState.value.dialog)
        assertEquals(listOf("Renamed"), db.programDao().daysForPlan(plan).map { it.title })
    }
    @Test fun `Add rest moves and removes the viewed plans rest entry`() = runTest(dispatcher) {
        val plan = repo.plans.createAuthoredPlan("Plan", "Lift")
        repo.plans.publishCreatedAuthoredPlan(plan)
        val vm = viewModel()
        advanceUntilIdle()
        vm.addRest()
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.uiState.value.days.map { it.dayId })
        val rest = vm.uiState.value.days.last()
        assertEquals(ProgramDayKind.REST, rest.kind)
        assertEquals("Rest", rest.title)
        assertEquals(0, rest.liftCount)
        assertEquals(0, rest.setCount)
        assertNull(rest.cardioActivity)
        vm.openDayMenu("B")
        vm.moveDay("B", true)
        advanceUntilIdle()
        assertNull(vm.uiState.value.menuDayId)
        assertEquals(listOf("B", "A"), vm.uiState.value.days.map { it.dayId })
        vm.removeDay("B")
        vm.saveDayDialog()
        advanceUntilIdle()
        assertEquals(listOf("A"), vm.uiState.value.days.map { it.dayId })
    }

}
