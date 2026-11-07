package cloud.trotter.log.strength.ui.plan

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.ui.components.LOADING_REVEAL_DELAY_MS
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.setup.GoalPreviewItem
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.CardioActivity
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
class PlanScreenTest {

    @Test fun progressionSwitchesShowDefaultsAndForwardToggles() {
        var strength: Boolean? = null
        var cardio: Boolean? = null
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture(), actions().copy(onOfferStrengthChange = { strength = it }, onOfferCardioChange = { cardio = it }))
        } }
        val list = rule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Offer strength increases"))
        rule.onNodeWithText("Offer strength increases").assertIsOn().performClick()
        list.performScrollToNode(hasText("Offer cardio increases"))
        rule.onNodeWithText("Offer cardio increases").assertIsOff().performClick()
        rule.runOnIdle {
            assertEquals(false, strength)
            assertEquals(true, cardio)
        }
    }
    @get:Rule val rule = createComposeRule()
    private var previousWindowSize: String? = null

    @Before fun useRequestedWindowSize() {
        previousWindowSize = System.getProperty(REQUESTED_WINDOW_SIZE)
        System.setProperty(REQUESTED_WINDOW_SIZE, "true")
    }

    @After fun restoreWindowSize() {
        val previous = previousWindowSize
        if (previous == null) System.clearProperty(REQUESTED_WINDOW_SIZE)
        else System.setProperty(REQUESTED_WINDOW_SIZE, previous)
    }

    private fun actions() = PlanActions(onOpenDay = {}, onOpenPlans = {}, onSetUpProgram = {})

    @Test
    fun emptyPlanOffersAddDayEvenWhenHasProgramIsFalse() {
        var added = 0
        rule.setContent { AppTheme {
            PlanScreen(PlanUiState(planId = 7L, name = "Rehab"), actions().copy(onAddDay = { added++ }))
        } }
        rule.onNodeWithText("No days yet").assertExists()
        rule.onNodeWithText("Add a day, then its lifts.").assertExists()
        rule.onNodeWithText("Add day").performClick()
        assertEquals(1, added)
    }

    @Test
    fun missingPlanOffersSetup() {
        var opened = 0
        rule.setContent { AppTheme {
            PlanScreen(PlanUiState(), actions().copy(onSetUpProgram = { opened++ }))
        } }
        rule.onNodeWithText("No plan yet").assertExists()
        rule.onNodeWithText("Set up a plan").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun loadingRevealsTheSharedLabelAndKeepsSetupHidden() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { PlanScreen(PlanUiState(loading = true), actions()) } }
        rule.mainClock.advanceTimeBy(LOADING_REVEAL_DELAY_MS - 60)
        rule.onNodeWithText("Loading…").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(120)
        rule.onNodeWithText("Loading…").assertExists()
        rule.onNodeWithText("Set up a plan").assertDoesNotExist()
    }

    @Test
    fun generatedGoalsAndEmphasisAreVisibleAndTheWholeRowSelects() {
        var emphasis: GoalEmphasis? = null
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture().copy(
                goals = listOf(GoalPreviewItem("Barbell Back Squat", "235", false)),
                emphasis = GoalEmphasis.BALANCED,
            ), actions().copy(onEmphasisChange = { emphasis = it }))
        } }
        val list = rule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Goals"))
        rule.onNodeWithText("Goals").assertExists()
        rule.onNodeWithText("235").assertExists()
        list.performScrollToNode(hasText("Physique-leaning"))
        rule.onNodeWithText("Strength-leaning").assertExists()
        rule.onNodeWithText("Balanced strength + muscle").assertIsSelected()
        rule.onNodeWithText("Physique-leaning").assertExists()
        rule.onNodeWithText("Strength-leaning").performClick()
        assertEquals(GoalEmphasis.STRENGTH, emphasis)
    }

    @Test(timeout = 30_000)
    fun `tapping a goal opens its editor`() {
        val state = mutableStateOf(planScreenFixture().copy(
            goals = listOf(GoalPreviewItem("Barbell Back Squat", "235", false, "bb_back_squat", 235.0)),
            emphasis = GoalEmphasis.BALANCED,
        ))
        var opened: String? = null
        var saved = 0
        var canceled = 0
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(
                onOpenGoal = { id ->
                    opened = id
                    state.value = state.value.copy(goalDialog = PlanGoalDialog(id, "Barbell Back Squat", false, 235.0, "235", false))
                },
                onSaveGoal = { saved++; state.value = state.value.copy(goalDialog = null) },
                onDismissGoal = { canceled++; state.value = state.value.copy(goalDialog = null) },
            ))
        } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Barbell Back Squat"))
        rule.onNodeWithText("Barbell Back Squat").performClick()
        assertEquals("bb_back_squat", opened)
        rule.onNodeWithText("Barbell Back Squat goal").assertExists()
        rule.onNodeWithText("Goal (lb)").assertExists()
        rule.onNodeWithText("Calculated 235").assertExists()
        rule.onNodeWithText("Use calculated").assertDoesNotExist()
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.onNodeWithText("Barbell Back Squat").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(1, saved)
        assertEquals(1, canceled)
    }

    @Test(timeout = 30_000)
    fun `opening and recomposing the goal editor do not emit draft changes`() {
        val dialog = PlanGoalDialog("bb_back_squat", "Barbell Back Squat", false, 235.0, "235", false)
        val state = mutableStateOf(planScreenFixture().copy(goalDialog = dialog))
        val drafts = mutableListOf<Double?>()
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(
                onGoalDraft = { lb ->
                    drafts += lb
                    state.value = state.value.copy(goalDialog = state.value.goalDialog?.copy(draftLb = lb))
                },
                onDismissGoal = { state.value = state.value.copy(goalDialog = null) },
            ))
        } }
        rule.runOnIdle {
            assertEquals(emptyList<Double?>(), drafts)
            state.value = state.value.copy(offerStrengthIncreases = false, offerCardioIncreases = true)
        }
        rule.runOnIdle {
            assertEquals(dialog, state.value.goalDialog)
            assertEquals(emptyList<Double?>(), drafts)
        }
        rule.onNodeWithText("Goal (lb)").performTextReplacement("200")
        rule.runOnIdle {
            assertEquals(listOf(200.0), drafts)
            assertEquals(200.0, state.value.goalDialog!!.draftLb!!, 0.0)
        }
        rule.onNodeWithText("Cancel").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        val list = rule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Offer strength increases"))
        rule.onNodeWithText("Offer strength increases").assertIsOff()
        list.performScrollToNode(hasText("Offer cardio increases"))
        rule.onNodeWithText("Offer cardio increases").assertIsOn()
    }

    @Test(timeout = 30_000)
    fun `an edited goal offers use calculated`() {
        var reset = 0
        val state = mutableStateOf(planScreenFixture().copy(
            goals = listOf(GoalPreviewItem("Barbell Back Squat", "200", false, "bb_back_squat", 200.0, "235")),
            emphasis = GoalEmphasis.BALANCED,
        ))
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(
                onOpenGoal = { id ->
                    state.value = state.value.copy(goalDialog = PlanGoalDialog(id, "Barbell Back Squat", false, 200.0, "235", true))
                },
                onUseCalculatedGoal = { reset++; state.value = state.value.copy(goalDialog = null) },
            ))
        } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Barbell Back Squat"))
        rule.onNodeWithText("Calculated 235").assertExists()
        rule.onNodeWithText("200").assertExists()
        rule.onNodeWithText("Barbell Back Squat").performClick()
        rule.onNodeWithText("Barbell Back Squat goal").assertExists()
        rule.onNode(hasText("Calculated 235") and hasAnyAncestor(isDialog())).assertExists()
        rule.onNodeWithText("Use calculated").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(1, reset)
    }

    @Test(timeout = 30_000)
    fun `goal editor converts kg per hand drafts and disables save for an empty draft`() {
        val state = mutableStateOf(planScreenFixture().copy(
            unit = WeightUnit.KG,
            goalDialog = PlanGoalDialog("incline_db", "Incline DB Press", true, WeightUnit.KG.toLb(30.0), "35", false),
        ))
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(onGoalDraft = { lb ->
                state.value = state.value.copy(goalDialog = state.value.goalDialog!!.copy(draftLb = lb))
            }))
        } }
        rule.onNodeWithText("Goal (kg/hand)").performTextReplacement("32.5")
        rule.runOnIdle { assertEquals(WeightUnit.KG.toLb(32.5), state.value.goalDialog!!.draftLb!!, 0.0) }
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Goal (kg/hand)").performTextReplacement("")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(null, state.value.goalDialog!!.draftLb) }
    }

    @Test(timeout = 30_000)
    fun `zero goal drafts stay invalid through rounding and Done in pounds`() {
        assertZeroGoalRejected(WeightUnit.LB)
    }

    @Test(timeout = 30_000)
    fun `zero goal drafts stay invalid through rounding and Done in kilograms`() {
        assertZeroGoalRejected(WeightUnit.KG)
    }

    private fun assertZeroGoalRejected(unit: WeightUnit) {
        val state = mutableStateOf(planScreenFixture().copy(
            unit = unit,
            goalDialog = PlanGoalDialog("bb_back_squat", "Barbell Back Squat", false, 200.0, "235", true),
        ))
        val saved = mutableListOf<Double?>()
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(
                onGoalDraft = { lb -> state.value = state.value.copy(goalDialog = state.value.goalDialog!!.copy(draftLb = lb)) },
                onSaveGoal = { saved += state.value.goalDialog!!.draftLb },
            ))
        } }
        val field = rule.onNodeWithText(if (unit == WeightUnit.LB) "Goal (lb)" else "Goal (kg)")
        field.performTextReplacement("0")
        rule.onNodeWithText("0").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(null, state.value.goalDialog!!.draftLb) }
        field.performImeAction()
        rule.onNodeWithText("0").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled().performClick()
        rule.runOnIdle {
            assertEquals(null, state.value.goalDialog!!.draftLb)
            assertEquals(emptyList<Double?>(), saved)
        }
        field.performTextReplacement("200")
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf(unit.toLb(200.0)), saved) }
    }

    @Test(timeout = 30_000)
    fun `goal editor enforces the canonical ceiling in pounds`() {
        assertGoalCeiling(WeightUnit.LB, "2000.1", "2000")
    }

    @Test(timeout = 30_000)
    fun `goal editor enforces the canonical ceiling in kilograms`() {
        assertGoalCeiling(WeightUnit.KG, "907.2", "907")
    }

    private fun assertGoalCeiling(unit: WeightUnit, overLimit: String, roundsToLimit: String) {
        val state = mutableStateOf(planScreenFixture().copy(
            unit = unit,
            goalDialog = PlanGoalDialog("bb_back_squat", "Barbell Back Squat", false, 2500.0, "235", true),
        ))
        val saved = mutableListOf<Double?>()
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(
                onGoalDraft = { lb -> state.value = state.value.copy(goalDialog = state.value.goalDialog!!.copy(draftLb = lb)) },
                onSaveGoal = { saved += state.value.goalDialog!!.draftLb },
            ))
        } }
        rule.onNodeWithText("Save").assertIsNotEnabled()
        val field = rule.onNodeWithText(if (unit == WeightUnit.LB) "Goal (lb)" else "Goal (kg)")
        for (invalid in listOf(overLimit, "2500", "-1", "NaN", "Infinity")) {
            field.performTextReplacement(invalid)
            rule.onNodeWithText("Save").assertIsNotEnabled()
            field.performImeAction()
            rule.onNodeWithText(invalid).assertExists()
            rule.onNodeWithText("Save").assertIsNotEnabled().performClick()
            rule.runOnIdle {
                assertEquals(null, state.value.goalDialog!!.draftLb)
                assertEquals(emptyList<Double?>(), saved)
            }
        }
        for (valid in listOf(roundsToLimit, unit.fromLb(PlanRecipeJson.MAX_GOAL_LB).toString())) {
            field.performTextReplacement(valid)
            rule.runOnIdle { assertEquals(PlanRecipeJson.MAX_GOAL_LB, state.value.goalDialog!!.draftLb!!, 0.0) }
            field.performImeAction()
            rule.onNodeWithText("Save").assertIsEnabled().performClick()
        }
        rule.runOnIdle { assertEquals(List(2) { PlanRecipeJson.MAX_GOAL_LB }, saved) }
    }

    @Test
    fun authoredPlansHaveNoGoalsOrEmphasis() {
        rule.setContent { AppTheme { PlanScreen(planScreenFixture(), actions()) } }
        rule.onNodeWithText("Goals").assertDoesNotExist()
        rule.onNodeWithText("Emphasis").assertDoesNotExist()
        rule.onNodeWithText("Strength-leaning").assertDoesNotExist()
    }

    @Test
    fun dayRowCarriesTheDisplayedPlanAndDay() {
        val selected = mutableListOf<DaySelection>()
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture().copy(planId = 7L, days = listOf(
                PlanDay("A", 0, "Squat", 1, 3, true),
            )), actions().copy(onOpenDay = { selected += it }))
        } }

        rule.onNodeWithText("Squat").performClick()

        assertEquals(listOf(DaySelection(7L, "A")), selected)
    }

    @Test
    fun cardioOnlyDayShowsTheActivityAndSetCount() {
        val state = planScreenFixture().copy(days = listOf(
            PlanDay("A", 0, "Run", 0, 0, true, cardioActivity = CardioActivity.ROAD_RUN, cardioSetCount = 5),
        ))
        rule.setContent { AppTheme { PlanScreen(state, actions()) } }
        rule.onNodeWithText("Road run · 5 sets").assertExists()
        rule.onNodeWithText("0 lifts, 0 sets").assertDoesNotExist()
    }

    @Test
    fun authoredPlanOffersDayActionsAndHidesRemovalForTheLastDay() {
        val renamed = mutableListOf<String>()
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture(menu = "A"), actions().copy(onRenameDay = { renamed += it }))
        } }
        rule.onNodeWithText("Rename").performClick()
        assertEquals(listOf("A"), renamed)
        rule.onNodeWithText("Remove").assertDoesNotExist()
        // The controlled menu remains open until its action updates state.
        rule.onNodeWithText("Add day").assertExists()
    }

    @Test
    fun generatedPlanOffersDayMenuAndAddAction() {
        rule.setContent { AppTheme { PlanScreen(planScreenFixture(), actions()) } }
        rule.onNodeWithText("Add day").assertExists()
        rule.onNodeWithContentDescription("Day options").assertExists()
        rule.onNodeWithText("Next").assertExists()
    }

    @Test
    fun sevenDaysHideAddDay() {
        rule.setContent { AppTheme { PlanScreen(planScreenFixture(count = 7), actions()) } }
        rule.onNodeWithText("Add day").assertDoesNotExist()
    }

    @Test
    fun authoredPlanOffersRemovalWhenAnotherDayRemains() {
        val removed = mutableListOf<String>()
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture(count = 2, menu = "A"), actions().copy(onRemoveDay = { removed += it }))
        } }
        rule.onNodeWithText("Remove").performClick()
        assertEquals(listOf("A"), removed)
    }

    private fun mixedState(strengthCount: Int = 1, menu: String? = null): PlanUiState {
        val strength = (0 until strengthCount).map { index ->
            val id = ('A' + index).toString()
            ProgramDay(id, "Day $id", "", emptyList())
        }
        val cardio = listOf("C1", "C2").map { id ->
            ProgramDay(id, "Day $id", "", emptyList(), kind = ProgramDayKind.CARDIO)
        }
        return planScreenFixture(menu = menu).copy(days = PlanScreenBuilder.days(
            Program(strength + cardio), "A", LifterConfig(), ExerciseCatalog.CODE_ONLY,
        ))
    }

    @Test
    fun strengthDayOffersRemoveWhenOnlyCardioDaysRemain() {
        val removed = mutableListOf<String>()
        rule.setContent { AppTheme {
            PlanScreen(mixedState(menu = "A"), actions().copy(onRemoveDay = { removed += it }))
        } }
        rule.onNodeWithText("Remove").performClick()
        assertEquals(listOf("A"), removed)
    }

    @Test
    fun standaloneCardioDayOffersRenameAndRemove() {
        val renamed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        rule.setContent { AppTheme {
            PlanScreen(mixedState(menu = "C1"), actions().copy(
                onRenameDay = { renamed += it }, onRemoveDay = { removed += it },
            ))
        } }
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithText("Remove").performClick()
        assertEquals(listOf("C1"), renamed)
        assertEquals(listOf("C1"), removed)
    }

    @Test
    fun sixStrengthDaysAndTwoCardioDaysStillOfferAddDay() {
        rule.setContent { AppTheme { PlanScreen(mixedState(strengthCount = 6), actions()) } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Add day"))
        rule.onNodeWithText("Add day").assertExists()
    }

    @Test(timeout = 30_000)
    fun removalExplainsWhatIsDeletedAndThatHistoryIsKept() {
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture(count = 2).copy(dialog = "remove", dialogDayId = "B", dayTitleDraft = "Day B"), actions())
        } }
        rule.onNodeWithText("Remove Day B and its exercises? History is kept.").assertExists()
    }
}

/** Robolectric's full-screen frame for wrap-content dialogs can prevent text fields from settling. */
private const val REQUESTED_WINDOW_SIZE = "robolectric.windowManager.useRequestedSize"
