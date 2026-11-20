package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class HoldTimerSetScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun state(hold: HoldState? = null, weight: Double = 0.0): DayUiState {
        val base = workoutSetSingleFixture()
        val card = base.exercises.single().copy(
            title = "Plank", exerciseId = "plank", tracking = TrackingType.TIMED,
            headerTarget = HeaderTarget.Prescribed("2 × 0:30", eachSide = true),
            rows = List(2) { SetRowState(it, "${it + 1}", false, weight, 0, false, seconds = 30) },
            focusIndex = 0,
        )
        return base.copy(exercises = listOf(card), hold = hold, executionBusy = hold != null)
    }

    private fun hold(heldSeconds: Int = 12, running: Boolean = true, sides: List<Int> = emptyList()) = HoldState(
        HoldEvent(dayId = "A", programExerciseId = 1L, exerciseId = "plank", setIndex = 0,
            targetSeconds = 30, sides = 2, startedAtMillis = 1_000L, wallStart = 1_000L,
            elapsedStart = 1_000L, bootCount = 1, running = running, heldSeconds = sides),
        heldSeconds,
    )

    private fun show(state: DayUiState, actions: DayActions = workoutActions()) {
        rule.setContent { AppTheme { WorkoutSetHost(state.withWorkoutSelection(), actions) } }
    }

    @Test fun `an idle hold offers Start and keeps its editable target`() {
        var started: Pair<Long, Int>? = null
        show(state(), workoutActions().copy(onStartHold = { id, index -> started = id to index }))
        rule.onNodeWithText("30 s").assertIsDisplayed()
        rule.onNodeWithContentDescription("Edit set 1").assertIsEnabled()
        rule.onNodeWithText("Each side").assertIsDisplayed()
        rule.onNodeWithText("Log 30 s").assertDoesNotExist()
        rule.onNodeWithTag("workout-action-filled").assert(hasText("Start")).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1L to 0, started) }
    }

    @Test fun `a running hold counts up toward its target`() {
        var stops = 0
        show(state(hold()), workoutActions().copy(onStopHold = { stops++ }))
        rule.onNodeWithText("0:12").assertIsDisplayed()
        rule.onNodeWithText("Held").assertIsDisplayed()
        rule.onNodeWithText("Target 0:30").assertIsDisplayed()
        rule.onNodeWithText("Side 1 of 2").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithContentDescription("Edit set 1").assertDoesNotExist()
        var node: SemanticsNode? = rule.onNodeWithTag("workout-hero", useUnmergedTree = true).fetchSemanticsNode()
        while (node != null) {
            assertFalse(node.config.contains(SemanticsProperties.LiveRegion))
            node = node.parent
        }
        rule.onNodeWithTag("workout-action-tonal").assert(hasText("Stop")).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun `between sides the hold offers Start for the second side`() {
        var started: Pair<Long, Int>? = null
        show(state(hold(heldSeconds = 0, running = false, sides = listOf(30))),
            workoutActions().copy(onStartHold = { id, index -> started = id to index }))
        rule.onNodeWithText("Side 2 of 2").assertIsDisplayed()
        rule.onNodeWithText("0:00").assertIsDisplayed()
        rule.onNodeWithTag("workout-action-filled").assert(hasText("Start")).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1L to 0, started) }
    }

    @Test fun `a loaded hold shows its weight while it runs`() {
        show(state(hold(), weight = 25.0))
        rule.onNodeWithText("at 25 lb").assertIsDisplayed()
    }
}
