package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.TouchTargets.assertEveryTouchTargetIsAtLeast48dp
import cloud.trotter.log.strength.ui.TouchTargets.assertNoOverlappingTouchTargets
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pager targets reserve their layout space; no old row overlap exceptions remain. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TouchTargetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pagerNavigationAndLoggingHaveMaterialTargets() {
        val state = mutableStateOf(pagerFixture())
        val actions = pagerActions().copy(onOpenSetEditor = { id, index ->
            state.value = state.value.copy(editSetId = id, editSetIndex = index)
        })
        rule.setContent { AppTheme { DayScreen(state.value, actions, DayEditUiState(), pagerEditActions()) } }
        rule.onNodeWithContentDescription("Day menu").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Lift 2 menu").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("Log 95 × 5").assertHeightIsAtLeast(56.dp)
        rule.assertEveryTouchTargetIsAtLeast48dp()
        rule.assertNoOverlappingTouchTargets()
        rule.onNodeWithText("95 × 5").performClick()
        rule.onNodeWithContentDescription("Set menu").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.assertEveryTouchTargetIsAtLeast48dp()
        rule.assertNoOverlappingTouchTargets()
    }
    @Test fun restPillControlsKeepTheirTargetsApart() {
        rule.setContent {
            AppTheme {
                DayScreen(pagerFixture().copy(rest = pagerRest()), pagerActions(), DayEditUiState(), pagerEditActions())
            }
        }
        rule.assertEveryTouchTargetIsAtLeast48dp()
        rule.assertNoOverlappingTouchTargets()
    }
}
