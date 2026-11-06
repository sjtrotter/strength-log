package cloud.trotter.log.strength.ui.setup

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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

        listOf("Units", "Distance", "Theme", "Keep screen on during workouts", "Rest timer",
            "Rest timer on watch", "Rest timer on phone", "Create custom exercise", "Backup and export", "Licenses").forEach { label ->
            list.performScrollToNode(hasText(label))
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }
        list.performScrollToNode(hasText("Rest timer"))
        composeTestRule.onNodeWithText("Rest timer").assertHasNoClickAction()
    }

    @Test fun distanceSegmentsShowMiAndKmAndForwardTheChoice() {
        var chosen: DistanceUnit? = null
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupUiState(), setupActions().copy(onDistanceUnitChange = { chosen = it }))
        } }
        composeTestRule.onNodeWithText("Distance").assertIsDisplayed()
        composeTestRule.onNodeWithText("mi").assertIsSelected()
        composeTestRule.onNodeWithText("km").performClick()
        assertEquals(DistanceUnit.KM, chosen)
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
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Backup and export"))
        composeTestRule.onNodeWithText("Backup and export").performClick()
        assertEquals(1, backups)
    }

    @Test
    fun backupIsVisibleWithoutScrollingAndPrecedesResetToDefaults() {
        composeTestRule.setContent { AppTheme { SetupScreen(SetupUiState(), setupActions()) } }
        composeTestRule.onNodeWithText("Backup and export").assertIsDisplayed()
        val list = composeTestRule.onNode(hasScrollAction())
        val encountered = linkedSetOf<String>()
        while (true) {
            val node = list.fetchSemanticsNode()
            val labels = listOf("Backup and export", "Reset to defaults").mapNotNull { label ->
                composeTestRule.onAllNodes(hasText(label)).fetchSemanticsNodes().firstOrNull()
                    ?.takeIf { it.boundsInRoot.overlaps(node.boundsInRoot) }?.let { label to it.boundsInRoot.top }
            }
            labels.sortedBy { it.second }.forEach { encountered += it.first }
            val scroll = node.config[SemanticsProperties.VerticalScrollAxisRange]
            if (scroll.value() >= scroll.maxValue()) break
            list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, node.size.height / 2f) }
        }
        assertTrue(encountered.contains("Reset to defaults"))
        assertEquals(listOf("Backup and export", "Reset to defaults"), encountered.toList())
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
        composeTestRule.onNodeWithText("Keep screen on during workouts").performClick()
        composeTestRule.runOnIdle { assertEquals(true, enabled) }
    }

    @Test
    fun restDialogSavesMinutesAndSecondsThroughTheExistingCallback() {
        var saved: Pair<RestCategory, Int>? = null
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupStateBuilder.buildUiState(unit = WeightUnit.LB), setupActions().copy(
                onRestOverrideChange = { kind, seconds -> saved = kind to seconds },
            ), openRestTimer = true)
        } }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Top set"))
        composeTestRule.onNodeWithText("Top set").performClick()
        // Typing into these fields is covered by restDurationSecondsParsesAndBoundsTheDraft, so
        // this saves the prefilled draft through the callback.
        val shown = SetupStateBuilder.buildUiState(unit = WeightUnit.LB).restCategories
            .single { it.category == RestCategory.TOP }.seconds
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.runOnIdle { assertEquals(RestCategory.TOP to shown, saved) }
        composeTestRule.onNodeWithText("Minutes").assertDoesNotExist()
    }

    @Test
    fun restDurationSecondsParsesAndBoundsTheDraft() {
        assertEquals(90, restDurationSeconds("1", "30"))
        assertEquals(90, restDurationSeconds(" 1 ", "30 "))
        assertEquals(0, restDurationSeconds("0", "0"))
        assertEquals(null, restDurationSeconds("", "30"))
        assertEquals(null, restDurationSeconds("1", "60"))
        assertEquals(null, restDurationSeconds("1", "-1"))
        assertEquals(null, restDurationSeconds((RestPolicy.MAX_REST_SECONDS / 60 + 1).toString(), "0"))
        assertEquals(RestPolicy.MAX_REST_SECONDS, restDurationSeconds(
            (RestPolicy.MAX_REST_SECONDS / 60).toString(), (RestPolicy.MAX_REST_SECONDS % 60).toString()))
    }

    @Test
    fun restDialogCancelDoesNotWrite() {
        var writes = 0
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupStateBuilder.buildUiState(unit = WeightUnit.LB), setupActions().copy(
                onRestOverrideChange = { _, _ -> writes++ },
            ), openRestTimer = true)
        } }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Top set"))
        composeTestRule.onNodeWithText("Top set").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Minutes").assertDoesNotExist()
        assertEquals(0, writes)
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
