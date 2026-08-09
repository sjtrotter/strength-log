package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.strength.wear.theme.WearTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DialSemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `every dial tap is one named merged node and invokes its callback once`() {
        val actions = DialTap.entries.filterNot { it == DialTap.NONE }
        val current = mutableStateOf(actions.first())
        var taps = 0
        composeTestRule.setContent {
            WearTrackerTheme { Dial(state(current.value), onTap = { taps++ }) }
        }

        actions.forEach { tap ->
            composeTestRule.runOnIdle { current.value = tap }
            val node = composeTestRule.onNode(hasClickLabel(tap.accessibilityClickLabel!!))
            node.assertHasClickAction().assertTextContains("ACTION")
            node.performClick()
            assertEquals("$tap callback count", actions.indexOf(tap) + 1, taps)
        }
    }

    @Test
    fun `undo long click exists only with a target and uses the hold callback`() {
        val target = UndoTarget(1, 2)
        val currentHold = mutableStateOf<DialHold?>(hold(target))
        var received: UndoTarget? = null
        composeTestRule.setContent {
            WearTrackerTheme {
                Dial(
                    state = state(DialTap.START_SET, hold = currentHold.value),
                    onTap = {},
                    onHoldComplete = { received = it },
                )
            }
        }

        composeTestRule.onNode(hasLongClickLabel("undo last set"))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(target, received)

        composeTestRule.runOnIdle { currentHold.value = null }
        composeTestRule.onNode(hasClickLabel("start set")).assertExists()
        composeTestRule.onAllNodes(hasLongClickLabel("undo last set")).assertCountEquals(0)
    }

    @Test
    fun `none keeps merged copy without exposing a click action`() {
        composeTestRule.setContent {
            WearTrackerTheme { Dial(state(DialTap.NONE), onTap = {}) }
        }

        composeTestRule.onNodeWithText("ACTION").assertHasNoClickAction()
    }

    private fun state(tap: DialTap, hold: DialHold? = null) = DialUiState(
        screen = when (tap) {
            DialTap.OPEN_WORKOUT -> DialScreen.OVERVIEW
            DialTap.START_SET, DialTap.CONFIRM_SWAP, DialTap.NONE -> DialScreen.READY
            DialTap.TICK -> DialScreen.LIFTING
            DialTap.SKIP_REST -> DialScreen.REST
            DialTap.DISMISS -> DialScreen.DAY_DONE
        },
        accentIndex = 0,
        cycle = emptyList(),
        dayProgress = 0f,
        rounds = emptyList(),
        arc = null,
        topBand = null,
        bottomBand = null,
        disc = DiscContent(
            style = DiscStyle.FILLED,
            lines = listOf(DiscLine("ACTION", DialTextRole.DISC_LABEL, DialTone.ON_DISC)),
        ),
        bloom = false,
        tap = tap,
        hold = hold,
    )

    private fun hold(target: UndoTarget) = DialHold(
        target = target,
        disc = DiscContent(DiscStyle.FLAT, emptyList()),
    )

    private fun hasClickLabel(label: String) = SemanticsMatcher("click label '$label'") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun hasLongClickLabel(label: String) = SemanticsMatcher("long-click label '$label'") { node ->
        node.config.getOrNull(SemanticsActions.OnLongClick)?.label == label
    }
}
