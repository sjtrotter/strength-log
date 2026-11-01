package cloud.trotter.log.strength.ui.setup

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Setup's controls sit under five overlines. Pinned here so a later edit can't
 * quietly lose one of them, and so a section rule stays a section rule — a
 * heading that grew a click action would be a phantom target on a screen that
 * is otherwise all controls.
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

        val list = composeTestRule.onNode(hasScrollAction())
        while (true) {
            composeTestRule.onNodeWithText("Cardio").assertDoesNotExist()
            composeTestRule.onNodeWithText("Outdoor run").assertDoesNotExist()
            val node = list.fetchSemanticsNode()
            val scroll = node.config[SemanticsProperties.VerticalScrollAxisRange]
            if (scroll.value() >= scroll.maxValue()) break
            list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, node.size.height / 2f) }
        }

        // The list virtualizes, so each header has to be scrolled into range
        // before it exists at all. Exact (not substring) text matching keeps
        // "DATA" off the DATA / BACKUP button and "DISPLAY" off the unit switch.
        listOf("TRAINING", "DISPLAY", "REST TIMER", "DATA", "ABOUT").forEach { label ->
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            composeTestRule.onNodeWithText(label).assertExists().assertHasNoClickAction()
        }
    }

    @Test
    fun restTimerShortcutStillOpensAtItsSection() {
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions(), openRestTimer = true) }
        }
        composeTestRule.onNodeWithText("REST TIMER").assertIsDisplayed()
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

    @Test
    fun flagOffKeepsSetupWithoutKeepScreenOn() {
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupUiState(), setupActions(), workoutSetScreen = false)
        } }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("DATA"))
        composeTestRule.onNodeWithText("Keep screen on during workouts").assertDoesNotExist()
    }

    @Test
    fun flagOnKeepScreenOnSwitchUsesExistingPreferenceCallback() {
        var enabled: Boolean? = null
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupUiState(), setupActions().copy(onKeepScreenOnChange = { enabled = it }),
                workoutSetScreen = true)
        } }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Keep screen on during workouts"))
        composeTestRule.onNodeWithContentDescription("Keep screen on during workouts").performClick()
        composeTestRule.runOnIdle { assertEquals(true, enabled) }
    }

    private fun setupActions() = SetupActions(
        onBodyweightChange = {},
        onAgeChange = {},
        onLevelChange = {},
        onEmphasisChange = {},

        onUnitToggle = {},
        onRestTimerEnabledChange = {},
        onRestOverrideChange = { _, _ -> },
        onRestOverridesReset = {},
        onCreateCustomExercise = {},
        onOpenBackup = {},
        onOpenLicenses = {},
        onBack = {},
    )
}
