package cloud.trotter.log.strength.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTouchHeightIsAtLeast
import androidx.compose.ui.test.assertTouchWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
 * dispatch. Width is deliberately NOT asserted — Robolectric has no font
 * metrics, and M3's 58dp button minimum is an audit-accepted delta.
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
        val node = composeTestRule.onNodeWithText("Reset")
        node.assertTouchHeightIsAtLeast(48.dp)
        node.assertTouchWidthIsAtLeast(48.dp)
        node.performClick()
        assertEquals(1, clicks)

        val semantics = node.fetchSemanticsNode().config
        assertNull(
            "label text is the accessible name; a contentDescription would announce twice",
            semantics.getOrNull(SemanticsProperties.ContentDescription),
        )
    }

    @Test
    fun authoredStateActionKeepsItsButtonContractAfterTheOutlinedButtonRebuild() {
        var clicks = 0
        composeTestRule.setContent {
            AppTheme { NoProgramState(onSetUpProgram = { clicks++ }) }
        }
        val node = composeTestRule.onNodeWithText("RUN THE SETUP WIZARD")
        node.assertTouchHeightIsAtLeast(48.dp)
        node.performClick()
        assertEquals(1, clicks)

        val semantics = node.fetchSemanticsNode().config
        assertNull(
            "label text is the accessible name; a contentDescription would announce twice",
            semantics.getOrNull(SemanticsProperties.ContentDescription),
        )
    }
}
