package cloud.trotter.log.strength.ui.plan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.GoalTime
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.ui.components.distanceUnitFor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.RecentRace
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.transfer.backup.BackupCodec
import cloud.trotter.log.strength.transfer.backup.BackupDocument
import cloud.trotter.log.strength.transfer.backup.PlanBackup
import cloud.trotter.log.strength.transfer.backup.ProgramDayBackup
import cloud.trotter.log.strength.transfer.backup.SettingsBackup
import cloud.trotter.log.strength.transfer.backup.toSnapshot
import cloud.trotter.log.strength.ui.components.LocalDistanceUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class PlanCardioGoalScreenTest {
    @get:Rule val rule = createComposeRule()
    private var previousWindowSize: String? = null
    private val timed = PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960)
    private fun actions() = PlanActions(onOpenDay = {}, onOpenPlans = {}, onSetUpProgram = {})

    @Before fun useRequestedWindowSize() {
        previousWindowSize = System.getProperty(WINDOW_SIZE)
        System.setProperty(WINDOW_SIZE, "true")
    }

    @After fun restoreWindowSize() {
        val previous = previousWindowSize
        if (previous == null) System.clearProperty(WINDOW_SIZE) else System.setProperty(WINDOW_SIZE, previous)
    }

    private fun assertProgress(best: Int?, text: String) {
        rule.setContent { AppTheme { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.MI) {
            PlanScreen(planScreenFixture().copy(goal = PlanGoalUi(timed, best, best != null && best < 960)), actions())
        } } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("2 mi under 16:00"))
        rule.onNodeWithText("2 mi under 16:00").assertExists()
        rule.onNodeWithText(text).assertExists()
    }

    @Test fun `the sprint goal editor saves a target or a blank without distance controls`() {
        val draft = mutableStateOf(SprintGoalDraft())
        var saved: SprintGoalDraft? = null
        rule.setContent { AppTheme { PlanSprintGoalEditor(draft.value, actions().copy(
            onSprintGoalDraft = { draft.value = it }, onSaveSprintGoal = { saved = draft.value })) } }
        rule.onNodeWithText("Distance").assertDoesNotExist()
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Time (s)").performTextReplacement("12.84")
        rule.onNodeWithText("Save").performClick()
        assertEquals("12.84", saved?.targetText)
        rule.onNodeWithText("Time (s)").performTextReplacement("abc")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Time (s)").performTextReplacement("")
        rule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test @Config(fontScale = 2f)
    fun largeFontSprintGoalEditor() {
        val draft = mutableStateOf(SprintGoalDraft("12.84", LocalDate.of(2026, 12, 1).toEpochDay()))
        rule.setContent { AppTheme { PlanSprintGoalEditor(draft.value, actions().copy(onSprintGoalDraft = { draft.value = it })) } }
        rule.onNodeWithText("Time (s)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Dec 1, 2026").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Remove date").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(null, draft.value.byEpochDay)
        rule.onNodeWithText("Add date").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Remove goal").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun `the forty yard sprint goal editor uses the event label`() {
        rule.setContent { AppTheme { PlanSprintGoalEditor(SprintGoalDraft(), actions(), metres = 37) } }
        rule.onNodeWithText("40 yd").assertIsDisplayed()
        rule.onNodeWithText("37 m").assertDoesNotExist()
    }

    @Test fun `timed goal shows the best and seconds over`() { assertProgress(984, "Best 16:24 · 24 s over") }
    @Test fun `equal time is not under`() { assertProgress(960, "Best 16:00 · not under") }
    @Test fun `faster time shows seconds under`() { assertProgress(950, "Best 15:50 · 10 s under") }
    @Test fun `no history shows no attempt`() { assertProgress(null, "No attempt yet") }
    @Test fun `minute gaps use a clock`() { assertProgress(1032, "Best 17:12 · 1:12 over") }

    @Test fun `shuttle goal shows counts and opens log attempt`() {
        val state = mutableStateOf(planScreenFixture().copy(goal = PlanGoalUi(PlanGoal.Shuttle("PACER", 52), 42, false)))
        var logged: String? = null
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(
                onOpenShuttleEntry = { state.value = state.value.copy(shuttleEntry = "") },
                onShuttleEntry = { state.value = state.value.copy(shuttleEntry = it) },
                onSaveShuttleEntry = { logged = state.value.shuttleEntry; state.value = state.value.copy(shuttleEntry = null) },
            ))
        } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("PACER · 52 shuttles"))
        rule.onNodeWithText("PACER · 52 shuttles").assertExists()
        rule.onNodeWithText("Best 42 · 10 to go").assertExists()
        rule.onNodeWithText("Log attempt").performClick()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Shuttles").performTextReplacement("44")
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals("44", logged)
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(goal = PlanGoalUi(PlanGoal.Shuttle("PACER", 52), 55, true)) }
        rule.onNodeWithText("Best 55 · goal met").assertExists()
    }

    @Test fun `add goal opens the editor with a duration draft`() {
        val state = mutableStateOf(planScreenFixture())
        val drafts = mutableListOf<PlanGoalDraft>()
        var saved: PlanGoalDraft? = null
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(
                onOpenCardioGoal = { state.value = state.value.copy(goalDraft = PlanGoalDraft()) },
                onCardioGoalDraft = { drafts += it; state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) },
                onSaveGoal = { saved = state.value.goalDraft; state.value = state.value.copy(goalDraft = null) },
                onDismissGoal = { state.value = state.value.copy(goalDraft = null) },
            ))
        } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Add goal"))
        rule.onNodeWithText("Add goal").performClick()
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText("16:00").assertExists()
        rule.runOnIdle { assertEquals(emptyList<PlanGoalDraft>(), drafts) }
        rule.onNodeWithText("Target time").performTextReplacement("15:30")
        rule.onNodeWithText("Target time").performImeAction()
        rule.onNodeWithText("Save").performClick()
        assertEquals(930, saved!!.underSeconds)
        rule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun `invalid duration disables saving and shuttle fields replace distance fields`() {
        val state = mutableStateOf(planScreenFixture().copy(goalDraft = PlanGoalDraft()))
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) }))
        } }
        rule.onNodeWithText("Target time").performTextReplacement("0:00")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Shuttle").performClick()
        rule.onNodeWithText("Target time").assertDoesNotExist()
        rule.onNodeWithText("Protocol").performTextReplacement("PACER")
        rule.onNodeWithText("Target shuttles").performTextReplacement("52")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { assertEquals(PlanGoal.Shuttle("PACER", 52), state.value.goalDraft!!.goal()) }
    }

    @Test fun `Just finish hides the time and A time needs one`() {
        val state = mutableStateOf(planScreenFixture().copy(goalDraft = PlanGoalDraft(metres = 21_100)))
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) }))
        } }
        rule.onNodeWithText("Just finish").performClick()
        rule.onNodeWithText("Target time").assertDoesNotExist()
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 21_100, null), state.value.goalDraft!!.goal()) }
        rule.onNodeWithText("A time").performClick()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Target time").performTextReplacement("1:45:00")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 21_100, 6300), state.value.goalDraft!!.goal()) }
    }

    @Test fun `both goals show dates and an existing goal can be removed`() {
        val goal = timed.copy(byEpochDay = LocalDate.of(2027, 1, 2).toEpochDay())
        val state = mutableStateOf(planScreenFixture().copy(goal = PlanGoalUi(goal, null, false)))
        var removed = 0
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(
                onOpenCardioGoal = { state.value = state.value.copy(goalDraft = PlanGoalDraft.from(goal)) },
                onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) },
                onRemoveGoal = { removed++ },
            ))
        } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("By Jan 2, 2027"))
        rule.onNodeWithText("By Jan 2, 2027").assertExists()
        rule.onNodeWithText("2 mi under 16:00").performClick()
        rule.onNode(hasText("Jan 2, 2027") and hasAnyAncestor(isDialog())).assertExists()
        rule.onNodeWithText("Remove date").performClick()
        rule.onNodeWithText("Add date").assertExists()
        rule.onNodeWithText("Remove goal").performClick()
        assertEquals(1, removed)
        rule.runOnIdle {
            state.value = state.value.copy(goalDraft = null,
                goal = PlanGoalUi(PlanGoal.Shuttle("PACER", 52, byEpochDay = goal.byEpochDay), null, false))
        }
        rule.onNodeWithText("By Jan 2, 2027").assertExists()
    }

    @Test fun `restored timed goal at minimum date opens the picker`() {
        assertRestoredDatePicker(timed.copy(byEpochDay = LocalDate.of(2000, 1, 1).toEpochDay()), "Jan 1, 2000")
    }

    @Test fun `restored timed goal at maximum date opens the picker`() {
        assertRestoredDatePicker(timed.copy(byEpochDay = LocalDate.of(2100, 12, 31).toEpochDay()), "Dec 31, 2100")
    }

    @Test fun `restored shuttle goal at minimum date opens the picker`() {
        assertRestoredDatePicker(PlanGoal.Shuttle("PACER", 52, byEpochDay = LocalDate.of(2000, 1, 1).toEpochDay()), "Jan 1, 2000")
    }

    @Test fun `restored shuttle goal at maximum date opens the picker`() {
        assertRestoredDatePicker(PlanGoal.Shuttle("PACER", 52, byEpochDay = LocalDate.of(2100, 12, 31).toEpochDay()), "Dec 31, 2100")
    }

    private fun assertRestoredDatePicker(goal: PlanGoal, date: String) {
        val codec = BackupCodec()
        val document = BackupDocument(
            settings = SettingsBackup(180, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
            plans = listOf(PlanBackup(1, "Plan", "AUTHORED", 1000, goalJson = PlanGoalJson.encode(goal),
                days = listOf(ProgramDayBackup("A", "Day A", "")))),
        )
        val plan = codec.decode(codec.encode(document)).toSnapshot().plans.single()
        val restored = requireNotNull(PlanGoalJson.decode(plan.goalJson))
        assertEquals(goal, restored)
        val state = mutableStateOf(planScreenFixture().copy(goal = PlanGoalUi(restored, null, false)))
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(
                onOpenCardioGoal = { state.value = state.value.copy(goalDraft = PlanGoalDraft.from(restored)) },
                onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) },
            ))
        } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("By $date"))
        rule.onNodeWithText("By $date").performClick()
        rule.onNode(hasText(date) and hasAnyAncestor(isDialog())).performClick()
        rule.onAllNodes(isDialog()).assertCountEquals(2)
        rule.onAllNodesWithText("Save")[1].assertIsEnabled().performClick()
        rule.onAllNodes(isDialog()).assertCountEquals(1)
        rule.runOnIdle { assertEquals(goal.byEpochDay, state.value.goalDraft!!.byEpochDay) }
    }

    @Test fun `out of range draft dates disable saving and open an unselected picker`() {
        val state = mutableStateOf(planScreenFixture().copy(goalDraft = PlanGoalDraft()))
        rule.setContent { AppTheme { PlanScreen(state.value, actions()) } }
        for (day in listOf(LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay(), Long.MIN_VALUE, Long.MAX_VALUE)) {
            for (timed in listOf(true, false)) {
                rule.runOnIdle {
                    state.value = state.value.copy(goalDraft = PlanGoalDraft(timed = timed, protocol = "PACER", byEpochDay = day))
                }
                rule.onNodeWithText("Save").assertIsNotEnabled()
                rule.onNodeWithText("Add date").performClick()
                rule.onAllNodes(isDialog()).assertCountEquals(2)
                rule.onAllNodesWithText("Save")[1].assertIsNotEnabled()
                rule.onAllNodesWithText("Cancel")[1].performClick()
                rule.onAllNodes(isDialog()).assertCountEquals(1)
            }
        }
    }

    @Test fun `an untimed goal shows its distance and best without a gap`() {
        val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 10000, null, null)
        val state = mutableStateOf(planScreenFixture().copy(goal = PlanGoalUi(goal, 3130, false)))
        var headline = ""
        rule.setContent { AppTheme { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.MI) {
            headline = distanceUnitFor(goal.activity).format(goal.metres)
            PlanScreen(state.value, actions())
        } } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(headline))
        rule.onNodeWithText(headline).assertExists()
        rule.onNodeWithText("Best ${ClockText.minutesSeconds(3130)}").assertExists()
        rule.runOnIdle { state.value = state.value.copy(goal = PlanGoalUi(goal, null, false)) }
        rule.onNodeWithText("No attempt yet").assertExists()
        val draft = PlanGoalDraft.from(goal)
        assertEquals(0, draft.underSeconds)
        // PR 7c: blank time saves Just finish
        assertEquals(goal, draft.goal())
    }

    @Test fun `marathon goals and predictions show hours`() {
        val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 42195, 12600)
        val a = SetupAnswers(branch = PlanBranch.RUN,
            run = RunAnswers(goal = RunGoal.MARATHON, recent = RecentRace(21097, 6600)))
        val plan = PlanEntity(1, "Marathon", PlanOrigin.GENERATED.name, 0, 0,
            recipeJson = SetupRecipeJson.encode(a, RunPhase.MARATHON_BUILD), goalJson = PlanGoalJson.encode(goal))
        val ui = PlanScreenBuilder.buildUiState(plan, Program(emptyList()), ExerciseCatalog(emptyList()), WeightUnit.LB, todayEpochDay = 0L)
        assertEquals(14449, ui.goal!!.predictedSeconds)
        var expected = ""
        var target = ""
        rule.setContent { AppTheme { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.MI) {
            expected = "Predicted 4:00:49 from ${distanceUnitFor(goal.activity).format(21097)} in 1:50:00"
            target = "${distanceUnitFor(goal.activity).format(goal.metres)} under 3:30:00"
            PlanScreen(planScreenFixture().copy(goal = ui.goal), actions())
        } } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(expected))
        rule.onNodeWithText(expected).assertExists()
        rule.onNodeWithText(target).assertExists()
    }

    @Test fun `hour long bests and gaps show hours`() {
        val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 42195, 12600)
        val state = mutableStateOf(planScreenFixture().copy(goal = PlanGoalUi(goal, 16800, false)))
        rule.setContent { AppTheme { PlanScreen(state.value, actions()) } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Best 4:40:00 · 1:10:00 over"))
        rule.onNodeWithText("Best 4:40:00 · 1:10:00 over").assertExists()
        rule.runOnIdle { state.value = state.value.copy(goal = PlanGoalUi(goal, 8400, true)) }
        rule.onNodeWithText("Best 2:20:00 · 1:10:00 under").assertExists()
    }

    @Test fun `the goal editor accepts hours`() {
        val state = mutableStateOf(planScreenFixture().copy(goalDraft = PlanGoalDraft(metres = 42195)))
        var saved: PlanGoal? = null
        rule.setContent { AppTheme { PlanScreen(state.value, actions().copy(
            onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) },
            onSaveGoal = { saved = state.value.goalDraft?.goal() },
        )) } }
        rule.onNodeWithText("Target time").performTextReplacement("3:30")
        rule.onNodeWithText("Target time").performImeAction()
        rule.onNodeWithText("Enter a time between 1:10:19 and 24:00:00").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(INVALID_TIME, state.value.goalDraft!!.underSeconds) }
        rule.onNodeWithText("Target time").performTextReplacement("3:30:00")
        rule.onNodeWithText("Target time").performImeAction()
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 42195, 12600), saved)
    }

    @Test fun `the goal editor accepts a fast 800 m time`() {
        val state = mutableStateOf(planScreenFixture().copy(goalDraft = PlanGoalDraft(metres = 800)))
        rule.setContent { AppTheme { PlanScreen(state.value, actions().copy(
            onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) },
        )) } }
        rule.onNodeWithText("Target time").performTextReplacement("1:41")
        rule.onNodeWithText("Target time").performImeAction()
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 800, 101), state.value.goalDraft!!.goal()) }
    }

    @Test fun `an outdoor ride of 40 km accepts one hour`() {
        val state = mutableStateOf(planScreenFixture().copy(goalDraft = PlanGoalDraft(
            activity = CardioActivity.OUTDOOR_RIDE.name, metres = 40_000, underSeconds = 3600)))
        rule.setContent { AppTheme { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.KM) {
            PlanScreen(state.value, actions().copy(
                onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) },
            ))
        } } }
        rule.onNodeWithText("Target time").performTextReplacement("1:00:00")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { assertEquals(PlanGoal.Timed(CardioActivity.OUTDOOR_RIDE, 40_000, 3600), state.value.goalDraft!!.goal()) }
        rule.onNodeWithText("Distance (km)").performTextReplacement("42.2")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { assertEquals(3600, state.value.goalDraft!!.underSeconds) }
    }

    @Test fun `switching a marathon editor to a ride shows the ride's range and accepts one hour`() {
        val state = mutableStateOf(planScreenFixture().copy(goalDraft = PlanGoalDraft(metres = 42_200, underSeconds = 12600)))
        rule.setContent { AppTheme { PlanScreen(state.value, actions().copy(
            onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) },
        )) } }
        rule.onNodeWithText("Target time").performTextReplacement("3:30")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Road run").performClick()
        rule.onNodeWithText("Outdoor ride").performClick()
        // PR 8: the cleared time shows its range, as setup does (#615)
        assertClearedPlanTime(timeRange(42_200, CardioActivity.OUTDOOR_RIDE))
        rule.onNodeWithText("Target time").performTextReplacement("1:00:00")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { assertEquals(PlanGoal.Timed(CardioActivity.OUTDOOR_RIDE, 42_200, 3600), state.value.goalDraft!!.goal()) }
        rule.onNodeWithText("Outdoor ride").performClick()
        rule.onNodeWithText("Stationary bike").performClick()
        rule.onNodeWithText("1:00:00").assertExists()
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { assertEquals(3600, state.value.goalDraft!!.underSeconds) }
    }

    @Test fun `switching a valid ride time to running invalidates it`() {
        val state = mutableStateOf(planScreenFixture().copy(goalDraft = PlanGoalDraft(
            activity = CardioActivity.OUTDOOR_RIDE.name, metres = 40_000, underSeconds = 3600)))
        rule.setContent { AppTheme { PlanScreen(state.value, actions().copy(
            onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) },
        )) } }
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Outdoor ride").performClick()
        rule.onNodeWithText("Road run").performClick()
        // PR 8: the cleared time shows its range, as setup does (#615)
        assertClearedPlanTime(timeRange(40_000, CardioActivity.ROAD_RUN))
        rule.runOnIdle { assertEquals(INVALID_TIME, state.value.goalDraft!!.underSeconds) }
    }

    @Test fun `distance changes clear valid and invalid time drafts at the new floor`() {
        val state = mutableStateOf(planScreenFixture().copy(goalDraft = PlanGoalDraft(
            activity = CardioActivity.TRACK_RUN.name, metres = 800, underSeconds = 101)))
        rule.setContent { AppTheme { PlanScreen(state.value, actions().copy(
            onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) },
        )) } }
        rule.onNodeWithText("Target time").performTextReplacement("1:41")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Distance (m)").performTextReplacement("1500")
        // PR 8: the cleared time shows its range, as setup does (#615)
        assertClearedPlanTime(timeRange(1500, CardioActivity.TRACK_RUN))
        rule.runOnIdle { assertEquals(INVALID_TIME, state.value.goalDraft!!.underSeconds) }
        rule.onNodeWithText("Target time").performTextReplacement("1:41")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Distance (m)").performTextReplacement("800")
        // PR 8: the cleared time shows its range, as setup does (#615)
        assertClearedPlanTime(timeRange(800, CardioActivity.TRACK_RUN))
        rule.onNodeWithText("Target time").performTextReplacement("1:41")
        rule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun `choosing A time shows the range until a time is typed`() {
        val state = mutableStateOf(planScreenFixture().copy(goalDraft = PlanGoalDraft(metres = 5000, underSeconds = 0)))
        rule.setContent { AppTheme { PlanScreen(state.value, actions().copy(
            onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it.edited(state.value.goalDraft)) },
        )) } }
        rule.onNodeWithText("A time").performClick()
        val range = timeRange(5000, CardioActivity.ROAD_RUN)
        assertClearedPlanTime(range)
        rule.onNodeWithText("Target time").performTextReplacement("25:00")
        val field = rule.onNodeWithText("Target time").fetchSemanticsNode()
        assertEquals(false, field.config.contains(SemanticsProperties.Error))
        rule.onNodeWithText(range).assertDoesNotExist()
        rule.onNodeWithText("Save").assertIsEnabled()
    }

    private fun timeRange(metres: Int, activity: CardioActivity): String =
        ApplicationProvider.getApplicationContext<Context>().getString(R.string.form_time_range,
            ClockText.duration(GoalTime.floorSeconds(metres, activity)), ClockText.duration(86_400))

    private fun assertClearedPlanTime(range: String) {
        val field = rule.onNodeWithText("Target time").fetchSemanticsNode()
        assertEquals("", field.config[SemanticsProperties.EditableText].text)
        // PR 8: the cleared time shows its range, as setup does (#615)
        assertEquals(true, field.config.contains(SemanticsProperties.Error))
        // PR 8: the cleared time shows its range, as setup does (#615)
        rule.onNodeWithText(range).assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    private companion object { const val WINDOW_SIZE = "robolectric.windowManager.useRequestedSize" }
}
