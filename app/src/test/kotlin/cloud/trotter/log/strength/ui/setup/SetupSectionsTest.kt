package cloud.trotter.log.strength.ui.setup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import cloud.trotter.log.strength.run.LocationAccess
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.progression.EffortScale
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

    @Test
    fun distanceRowShowsKilometersAndForwardsMiles() {
        var chosen: DistanceUnit? = null
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupUiState(distanceUnit = DistanceUnit.KM), setupActions().copy(onDistanceUnitChange = { chosen = it }))
        } }
        composeTestRule.onNodeWithText("Distance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kilometers").assertIsDisplayed()
        composeTestRule.onNodeWithText("Distance").performClick()
        composeTestRule.onNode(hasText("Kilometers") and hasAnyAncestor(isDialog())).assertIsSelected()
        composeTestRule.onNode(hasText("Miles") and hasAnyAncestor(isDialog())).performClick()
        composeTestRule.runOnIdle { assertEquals(DistanceUnit.MI, chosen) }
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun effortScaleRowShowsCurrentChoiceAndForwardsBothOptions() {
        val scale = mutableStateOf(EffortScale.WORDS)
        val choices = mutableListOf<EffortScale>()
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupUiState(effortScale = scale.value), setupActions().copy(onEffortScaleChange = {
                choices += it
                scale.value = it
            }))
        } }
        composeTestRule.onNodeWithText("Words").assertIsDisplayed()
        composeTestRule.onNodeWithText("Effort scale").performClick()
        composeTestRule.onNode(hasText("Words") and hasAnyAncestor(isDialog())).assertIsSelected()
        composeTestRule.onNode(hasText("Reps left") and hasAnyAncestor(isDialog())).performClick()
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        composeTestRule.onNodeWithText("Reps left").assertIsDisplayed()
        composeTestRule.onNodeWithText("Effort scale").performClick()
        composeTestRule.onNode(hasText("Reps left") and hasAnyAncestor(isDialog())).assertIsSelected()
        composeTestRule.onNode(hasText("Words") and hasAnyAncestor(isDialog())).performClick()
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        composeTestRule.onNodeWithText("Words").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(listOf(EffortScale.REPS_LEFT, EffortScale.WORDS), choices) }
    }

    @Test
    fun distanceDialogSurvivesRestorationAndCancelDoesNotWrite() {
        var writes = 0
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent { AppTheme {
            SetupScreen(SetupUiState(), setupActions().copy(onDistanceUnitChange = { writes++ }))
        } }
        composeTestRule.onNodeWithText("Distance").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNode(isDialog()).assertExists()
        composeTestRule.onNode(hasText("Miles") and hasAnyAncestor(isDialog())).assertIsSelected()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        composeTestRule.runOnIdle { assertEquals(0, writes) }
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

    @Test fun gpsDistanceShowsItsEnabledSwitchAndOutdoorDetail() {
        composeTestRule.setContent { AppTheme { SetupScreen(SetupUiState(gpsDistance = true), setupActions()) } }
        composeTestRule.onNodeWithText("GPS distance").assertIsOn()
        composeTestRule.onNodeWithText("Outdoor runs, walks and rides").assertExists()
    }

    @Test fun approximateLocationShowsThePreciseRequirement() {
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupUiState(gpsDistance = false, gpsAccess = LocationAccess.APPROXIMATE), setupActions())
        } }
        composeTestRule.onNodeWithText("Needs precise location").assertExists()
    }

    @Test fun preciseLocationEnablesGpsWithoutAsking() {
        var chosen: Boolean? = null
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupUiState(gpsDistance = false, gpsAccess = LocationAccess.PRECISE),
                setupActions().copy(onGpsDistanceChange = { chosen = it }))
        } }
        composeTestRule.onNodeWithText("GPS distance").performClick()
        composeTestRule.runOnIdle { assertEquals(true, chosen) }
    }

    @Test fun saveRouteDefaultsOffWithItsPhoneStorageDetail() {
        composeTestRule.setContent { AppTheme { SetupScreen(SetupUiState(), setupActions()) } }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Save route"))
        composeTestRule.onNodeWithText("Save route").assertIsDisplayed().assertIsOff()
        composeTestRule.onNodeWithText("Stored on this phone only").assertIsDisplayed()
    }

    @Test fun saveRouteIsDisabledWhenGpsDistanceIsOff() {
        composeTestRule.setContent { AppTheme { SetupScreen(SetupUiState(gpsDistance = false), setupActions()) } }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Save route"))
        composeTestRule.onNodeWithText("Save route").assertIsNotEnabled()
    }

    @Test fun saveRouteToggleForwardsItsEnabledValue() {
        var chosen: Boolean? = null
        composeTestRule.setContent { AppTheme {
            SetupScreen(SetupUiState(gpsDistance = true), setupActions().copy(onSaveRouteChange = { chosen = it }))
        } }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Save route"))
        composeTestRule.onNodeWithText("Save route").performClick()
        composeTestRule.runOnIdle { assertEquals(true, chosen) }
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
