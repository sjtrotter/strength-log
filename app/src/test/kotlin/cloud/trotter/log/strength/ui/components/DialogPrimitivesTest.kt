package cloud.trotter.log.strength.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.TouchTargets.assertEveryTouchTargetIsAtLeast48dp
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Error
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the contract the phase-3 M3 rebuild of the dialog/authored-state
 * actions must keep (PR #182 review): one button node whose accessible name
 * is the visible label alone, a >=48dp touch target, and a single click
 * dispatch. Width beyond the target minimum is deliberately NOT asserted —
 * Robolectric has no font metrics, and M3's 58dp button minimum is an
 * audit-accepted delta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DialogPrimitivesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dialogActionIsOneButtonNamedByItsLabelWithAFullTouchTarget() {
        var clicks = 0
        composeTestRule.setContent {
            AppTheme { DialogAction("Reset", Error) { clicks++ } }
        }
        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()

        val node = composeTestRule.onNodeWithText("Reset")
        node.performClick()
        assertEquals(1, clicks)

        assertNull(
            "label text is the accessible name; a contentDescription would announce twice",
            node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
    }

    @Test
    fun authoredStateActionKeepsItsButtonContractAfterTheOutlinedButtonRebuild() {
        var clicks = 0
        composeTestRule.setContent {
            AppTheme { NoProgramState(onSetUpProgram = { clicks++ }) }
        }
        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()

        val node = composeTestRule.onNodeWithText("RUN THE SETUP WIZARD")
        node.performClick()
        assertEquals(1, clicks)

        assertNull(
            "label text is the accessible name; a contentDescription would announce twice",
            node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
    }
}
