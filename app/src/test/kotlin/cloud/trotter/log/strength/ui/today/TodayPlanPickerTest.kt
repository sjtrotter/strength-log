package cloud.trotter.log.strength.ui.today

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.PlanSummary
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.text.TodayActionKind
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TodayPlanPickerTest {
    @get:Rule
    val rule = createComposeRule()

    private val plans = listOf(
        PlanSummary(1, "Strength", "B", "Day B", selected = true, archived = false, lastSessionAt = null),
        PlanSummary(2, "Running", "A", "Easy run", selected = false, archived = false, lastSessionAt = null),
    )
    private val radioRow = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun state(activePlans: List<PlanSummary> = plans) = TodayUiState(
        hasProgram = true,
        dayId = "B",
        dayIndex = 1,
        dayTitle = "Squat",
        planId = 1,
        planName = "Strength",
        activePlans = activePlans,
    )

    private fun actions() = TodayActions(
        onStart = {}, onOpenSettings = {}, onOpenLog = {}, onSetUpProgram = {},
    )

    @Test
    fun startAndCardioLinksCarryTheDisplayedPlanWhileAnotherPlanIsSelected() {
        val starts = mutableListOf<DaySelection>()
        val cardioLinks = mutableListOf<DaySelection>()
        val state = state(plans.map { it.copy(selected = it.id == 2L) }).copy(
            standaloneCardio = StandaloneCardio("C1", "Log cardio + core, 25 min"),
            actionLabel = UiText.TodayAction(TodayActionKind.START, "B", 0, 1),
        )
        rule.setContent { AppTheme {
            TodayScreen(state, actions().copy(onStart = { starts += it }, onOpenCardio = { cardioLinks += it }))
        } }

        rule.onNodeWithText("Start day B").performClick()
        rule.onNodeWithText("Log cardio + core, 25 min").performClick()

        assertEquals(listOf(DaySelection(1L, "B")), starts)
        assertEquals(listOf(DaySelection(1L, "C1")), cardioLinks)
    }

    @Test
    fun onePlanShowsNeitherItsNameNorTheSheet() {
        rule.setContent {
            AppTheme {
                TodayScreen(state(plans.take(1)).copy(planPickerOpen = true), actions())
            }
        }

        rule.onNodeWithText("Squat").assertExists()
        rule.onNodeWithText("Strength").assertDoesNotExist()
        rule.onNodeWithText("Switch plan").assertDoesNotExist()
        rule.onAllNodes(radioRow).assertCountEquals(0)
    }

    @Test
    fun twoPlansShowTheNameAndOpenRadioRowsWithEachNextDay() {
        val state = mutableStateOf(state())
        val selected = mutableListOf<Long>()
        rule.setContent {
            AppTheme {
                TodayScreen(
                    state.value,
                    actions().copy(
                        onShowPlanPicker = { state.value = state.value.copy(planPickerOpen = true) },
                        onSelectPlan = { selected += it },
                    ),
                )
            }
        }

        rule.onNodeWithText("Switch plan").assertDoesNotExist()
        rule.onNodeWithText("Strength").assertHasClickAction().performClick()

        rule.onNodeWithText("Switch plan").assertExists()
        rule.onAllNodes(radioRow).assertCountEquals(2)
        rule.onNode(radioRow and hasText("Strength")).assertIsSelected().assertHasClickAction()
        rule.onNode(radioRow and hasText("Running")).assertIsNotSelected().assertHasClickAction()
        rule.onNodeWithText("Next: Day B").assertExists()
        rule.onNodeWithText("Next: Easy run").assertExists()

        rule.onNode(radioRow and hasText("Running")).performClick()
        assertEquals(listOf(2L), selected)
    }

    @Test
    fun switchingStateShowsTheNewPlanAndDayAndRemovesTheSheet() {
        val state = mutableStateOf(state().copy(planPickerOpen = true))
        rule.setContent { AppTheme { TodayScreen(state.value, actions()) } }
        rule.onNodeWithText("Switch plan").assertExists()

        rule.runOnIdle {
            state.value = state.value.copy(
                dayId = "A",
                dayIndex = 0,
                dayTitle = "Easy run",
                planId = 2,
                planName = "Running",
                activePlans = plans.map { it.copy(selected = it.id == 2L) },
                planPickerOpen = false,
            )
        }

        rule.onNodeWithText("Running").assertHasClickAction()
        rule.onNodeWithText("Easy run").assertExists()
        rule.onNodeWithText("Strength").assertDoesNotExist()
        rule.onNodeWithText("Squat").assertDoesNotExist()
        rule.onNodeWithText("Switch plan").assertDoesNotExist()
        rule.onAllNodes(radioRow).assertCountEquals(0)
    }

    @Test
    fun selectedRadioCanChangeWhileTheHeaderKeepsTheDisplayedContentsPlanName() {
        val state = state(plans.map { it.copy(selected = it.id == 2L) })
        val pickerOpen = mutableStateOf(false)
        rule.setContent {
            AppTheme {
                TodayScreen(
                    state.copy(planPickerOpen = pickerOpen.value),
                    actions().copy(onShowPlanPicker = { pickerOpen.value = true }),
                )
            }
        }

        rule.onNodeWithText("Strength").assertHasClickAction().performClick()
        rule.onNodeWithText("Squat").assertExists()
        rule.onNode(radioRow and hasText("Strength")).assertIsNotSelected()
        rule.onNode(radioRow and hasText("Running")).assertIsSelected()
    }
}
