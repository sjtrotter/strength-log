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
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SetupSectionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun settingsContainsOnlyAppPreferences() {
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions()) }
        }

        val list = composeTestRule.onNode(hasScrollAction())
        while (true) {
            listOf("Bodyweight (", "Age", "Experience level", "Training emphasis", "Emphasis",
                "Strength-leaning", "Balanced strength + muscle", "Physique-leaning", "Novice", "Intermediate", "Advanced").forEach { label ->
                composeTestRule.onNodeWithText(label, substring = true).assertDoesNotExist()
            }
            val node = list.fetchSemanticsNode()
            val scroll = node.config[SemanticsProperties.VerticalScrollAxisRange]
            if (scroll.value() >= scroll.maxValue()) break
            list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, node.size.height / 2f) }
        }

        listOf("Units", "Theme", "Keep screen on during workouts", "Rest timer",
            "Rest timer on watch", "Rest timer on phone", "Create custom exercise", "Backup", "Licenses").forEach { label ->
            list.performScrollToNode(hasText(label))
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }
        list.performScrollToNode(hasText("Rest timer"))
        composeTestRule.onNodeWithText("Rest timer").assertHasNoClickAction()
    }

    @Test
    fun backupRowAndUnitSegmentsForwardTheirActions() {
        var backups = 0
        var unit: WeightUnit? = null
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupUiState(), setupActions().copy(
                onOpenBackup = { backups++ }, onUnitToggle = { unit = it },
            ))
        } }
        composeTestRule.onNodeWithText("kg").performClick()
        assertEquals(WeightUnit.KG, unit)
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Backup"))
        composeTestRule.onNodeWithText("Backup").performClick()
        assertEquals(1, backups)
    }

    @Test
    fun restTimerShortcutStillOpensAtItsSection() {
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions(), openRestTimer = true) }
        }
        composeTestRule.onNodeWithText("Rest timer").assertIsDisplayed()
    }

    @Test
    fun themeIsAResourcedSegmentedTrio() {
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
    fun keepScreenOnSwitchUsesExistingPreferenceCallback() {
        var enabled: Boolean? = null
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupUiState(), setupActions().copy(onKeepScreenOnChange = { enabled = it }))
        } }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Keep screen on during workouts"))
        composeTestRule.onNodeWithContentDescription("Keep screen on during workouts").performClick()
        composeTestRule.runOnIdle { assertEquals(true, enabled) }
    }

    private fun setupActions() = SetupActions(
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
