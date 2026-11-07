package cloud.trotter.log.strength.ui.plan

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.units.DistanceUnit
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
                onCardioGoalDraft = { drafts += it; state.value = state.value.copy(goalDraft = it) },
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
            PlanScreen(state.value, actions().copy(onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it) }))
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

    @Test fun `both goals show dates and an existing goal can be removed`() {
        val goal = timed.copy(byEpochDay = LocalDate.of(2027, 1, 2).toEpochDay())
        val state = mutableStateOf(planScreenFixture().copy(goal = PlanGoalUi(goal, null, false)))
        var removed = 0
        rule.setContent { AppTheme {
            PlanScreen(state.value, actions().copy(
                onOpenCardioGoal = { state.value = state.value.copy(goalDraft = PlanGoalDraft.from(goal)) },
                onCardioGoalDraft = { state.value = state.value.copy(goalDraft = it) },
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

    private companion object { const val WINDOW_SIZE = "robolectric.windowManager.useRequestedSize" }
}
