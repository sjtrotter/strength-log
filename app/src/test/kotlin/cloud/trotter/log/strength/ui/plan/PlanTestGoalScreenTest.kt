package cloud.trotter.log.strength.ui.plan

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.ui.log.JournalBuilder
import cloud.trotter.log.strength.ui.log.TestTrendCardView
import cloud.trotter.log.strength.ui.log.testResultFixture
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1200dp")
class PlanTestGoalScreenTest {
    @get:Rule val rule = createComposeRule()
    private var previousWindowSize: String? = null
    private fun actions() = PlanActions({}, {}, {})
    private val draft = TestGoalDraft(mapOf("aft_hrp" to "40", "aft_plank" to "3:40"))

    @Before fun useRequestedWindowSize() {
        previousWindowSize = System.getProperty(WINDOW_SIZE)
        System.setProperty(WINDOW_SIZE, "true")
    }

    @After fun restoreWindowSize() {
        val previous = previousWindowSize
        if (previous == null) System.clearProperty(WINDOW_SIZE) else System.setProperty(WINDOW_SIZE, previous)
    }

    @Test fun `the card reads Best and Required and the editor saves typed numbers`() {
        val state = mutableStateOf(testGoalPlanFixture())
        var saved: TestGoalDraft? = null
        rule.setContent { AppTheme { PlanScreen(state.value, actions().copy(
            onOpenTestGoal = { state.value = state.value.copy(testGoalDraft = draft) },
            onTestGoalDraft = { state.value = state.value.copy(testGoalDraft = it) },
            onSaveTestGoal = { saved = state.value.testGoalDraft },
        )) } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Best 31 · Required 40"))
        rule.onNodeWithText("Best 31 · Required 40").assertIsDisplayed()
        rule.onNodeWithText("No attempt yet · Required 3:40").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("By Nov 15, 2026").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("No attempt yet · Required 3:40").performClick()
        rule.onNodeWithText("Fitness test goal").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("AFT hand-release push-up")).performTextReplacement("45")
        rule.onNode(hasSetTextAction() and hasText("AFT plank")).performTextReplacement("3:30")
        rule.onNodeWithText("Save").performClick()
        assertEquals(mapOf("aft_hrp" to "45", "aft_plank" to "3:30"), saved?.required)
    }

    @Test fun `invalid required numbers show field errors and prevent Save`() {
        val state = mutableStateOf(draft)
        val ui = requireNotNull(testGoalPlanFixture().testGoal)
        rule.setContent { AppTheme { PlanTestGoalEditor(ui, state.value, actions().copy(onTestGoalDraft = { state.value = it })) } }
        val reps = rule.onNode(hasSetTextAction() and hasText("AFT hand-release push-up"))
        val hold = rule.onNode(hasSetTextAction() and hasText("AFT plank"))
        for (invalid in listOf("", "0", "3601", "1.5", "abc")) {
            reps.performTextReplacement(invalid)
            rule.onNodeWithText("Enter a number between 1 and 3600").assertIsDisplayed()
            rule.onNodeWithText("Save").assertIsNotEnabled()
        }
        reps.performTextReplacement("3600")
        rule.onNodeWithText("Save").assertIsEnabled()
        for (invalid in listOf("", "0:00", "60:01", "3:60", "abc")) {
            hold.performTextReplacement(invalid)
            rule.onNodeWithText("Enter a time between 0:01 and 60:00").assertIsDisplayed()
            rule.onNodeWithText("Save").assertIsNotEnabled()
        }
        hold.performTextReplacement("60:00")
        rule.onNodeWithText("Save").assertIsEnabled()
        hold.performTextReplacement("0:01")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { state.value = state.value.copy(byEpochDay = Long.MAX_VALUE) }
        rule.onNodeWithText("Valid range: Jan 1, 2000–Dec 31, 2100").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `a custom trend heading includes its stored window`() {
        val ref = TestRef("custom_pushup_window", 1, 60)
        val card = JournalBuilder.testTrends(listOf(testResultFixture(ref, 24, 1, 10), testResultFixture(ref, 27, 2, 20))).single()
        rule.setContent { AppTheme { TestTrendCardView(card) } }
        rule.onNodeWithText("Timed push-ups · 1:00").assertIsDisplayed()
        rule.onNodeWithText("Latest 27").assertIsDisplayed()
    }

    @Test @Config(fontScale = 2f, qualifiers = "w411dp-h1600dp")
    fun `the test editor supports large font and date removal`() {
        val ui = requireNotNull(testGoalPlanFixture().testGoal)
        val state = mutableStateOf(draft.copy(byEpochDay = ui.goal.byEpochDay))
        var removed = false
        var dismissed = false
        rule.setContent { AppTheme { PlanTestGoalEditor(ui, state.value, actions().copy(
            onTestGoalDraft = { state.value = it }, onRemoveTestGoal = { removed = true },
            onDismissTestGoal = { dismissed = true },
        )) } }
        rule.onNodeWithText("Remove date").performScrollTo().performClick()
        assertEquals(null, state.value.byEpochDay)
        rule.onNodeWithText("Add date").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Remove goal").performScrollTo().performClick()
        assertEquals(true, removed)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(true, dismissed)
    }

    private companion object { const val WINDOW_SIZE = "robolectric.windowManager.useRequestedSize" }
}
