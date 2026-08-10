package cloud.trotter.log.strength.ui.setup

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsSelected
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Setup stopped being a flat list (#128): its controls now sit under five
 * overlines. Pinned here so a later edit can't quietly lose one of them, and so
 * a section rule stays a section rule — a heading that grew a click action
 * would be a phantom target on a screen that is otherwise all controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SetupSectionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun everySectionHeaderIsPresentAndInert() {
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions()) }
        }

        // The list virtualises, so each header has to be scrolled into range
        // before it exists at all. Exact (not substring) text matching keeps
        // "DATA" off the DATA / BACKUP button and "DISPLAY" off the unit switch.
        listOf("TRAINING", "DISPLAY", "WATCH", "DATA", "ABOUT").forEach { label ->
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            composeTestRule.onNodeWithText(label).assertExists().assertHasNoClickAction()
        }
    }

    @Test
    fun themeIsAResourcedRadioTrio() {
        var chosen: ThemePreference? = null
        composeTestRule.setContent {
            AppTheme {
                SetupScreen(
                    state = SetupUiState(themePreference = ThemePreference.SYSTEM),
                    actions = setupActions().copy(onThemePreferenceChange = { chosen = it }),
                )
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("System"))
        composeTestRule.onNodeWithText("System").assertIsSelected()
        composeTestRule.onNodeWithText("Light").performClick()
        assertEquals(ThemePreference.LIGHT, chosen)
    }

    private fun setupActions() = SetupActions(
        onBodyweightChange = {},
        onAgeChange = {},
        onLevelChange = {},
        onEmphasisChange = {},
        onCardioModeChange = {},
        onCardioPlacementChange = {},
        onFiveKChange = {},
        onUnitToggle = {},
        onRestTimerEnabledChange = {},
        onRestOverrideChange = { _, _ -> },
        onRestOverridesReset = {},
        onRerunWizard = {},
        onCreateCustomExercise = {},
        onOpenBackup = {},
        onOpenLicenses = {},
        onBack = {},
    )
}
