package cloud.trotter.log.strength.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.TouchTargets.assertEveryTouchTargetIsAtLeast48dp
import cloud.trotter.log.strength.ui.TouchTargets.assertNoOverlappingTouchTargets
import cloud.trotter.log.strength.ui.backup.BackupActions
import cloud.trotter.log.strength.ui.backup.BackupScreen
import cloud.trotter.log.strength.ui.backup.BackupUiState
import cloud.trotter.log.strength.ui.customexercise.CustomExerciseActions
import cloud.trotter.log.strength.ui.customexercise.CustomExerciseScreen
import cloud.trotter.log.strength.ui.customexercise.CustomExerciseUiState
import cloud.trotter.log.strength.ui.day.ExercisePickerScreen
import cloud.trotter.log.strength.ui.licenses.LicensesScreen
import cloud.trotter.log.strength.ui.log.LogActions
import cloud.trotter.log.strength.ui.log.LogScreen
import cloud.trotter.log.strength.ui.log.LogUiState
import cloud.trotter.log.strength.ui.navigation.AppNavigationBar
import cloud.trotter.log.strength.ui.setup.SetupActions
import cloud.trotter.log.strength.ui.setup.SetupScreen
import cloud.trotter.log.strength.ui.setup.SetupUiState
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayDay
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen-level coverage for migrated chrome. These fixtures verify both halves
 * of the contract at the real call sites: every action owns at least 48dp, and
 * reserving that space never pushes its target onto a sibling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ChromeTouchTargetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun todaySettingsAndStartButtonKeepTheirTargetsApart() {
        composeTestRule.setContent {
            AppTheme { TodayScreen(state = todayState(), actions = todayActions()) }
        }

        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
        composeTestRule.assertNoOverlappingTouchTargets()
    }

    @Test
    fun theSetupListKeepsItsTargetsApart() {
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions()) }
        }

        listOf("Units", "Theme", "Keep screen on during workouts", "Rest timer on watch",
            "Rest timer on phone", "Warm-up", "Top set", "Back-off", "Accessory work",
            "Bodyweight and timed", "Reset to defaults", "Create custom exercise", "Backup and export", "Licenses").forEach { label ->
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
            composeTestRule.assertNoOverlappingTouchTargets()
        }
    }

    @Test
    fun theUnitsItemContainsBothSegmentedRows() {
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions()) }
        }

        val unitsItem = composeTestRule.onNodeWithText("Units").fetchSemanticsNode().boundsInRoot
        listOf("lb", "mi").forEach { label ->
            val row = composeTestRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup) and hasAnyDescendant(hasText(label)),
                useUnmergedTree = true,
            ).fetchSemanticsNode()
            val bounds = row.boundsInRoot
            assertEquals(row.size.height.toFloat(), bounds.height, 0.01f)
            assertTrue(
                "$label row $bounds must fit in Units $unitsItem",
                bounds.left >= unitsItem.left && bounds.right <= unitsItem.right &&
                    bounds.top >= unitsItem.top && bounds.bottom <= unitsItem.bottom,
            )
        }
    }

    @Test
    fun theUnitButtonsFitInsideTheUnitsItem() {
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions()) }
        }

        val unitsItem = composeTestRule.onNodeWithText("Units").fetchSemanticsNode().boundsInRoot
        listOf("lb", "kg", "mi", "km").forEach { label ->
            val node = composeTestRule.onNodeWithText(label).fetchSemanticsNode()
            val bounds = node.touchBoundsInRoot
            assertEquals(node.size.height.toFloat(), node.boundsInRoot.height, 0.01f)
            assertTrue(
                "$label target $bounds must fit in Units $unitsItem",
                bounds.left >= unitsItem.left && bounds.right <= unitsItem.right &&
                    bounds.top >= unitsItem.top && bounds.bottom <= unitsItem.bottom,
            )
        }
        val weightBottom = listOf("lb", "kg").maxOf { label ->
            composeTestRule.onNodeWithText(label).fetchSemanticsNode().touchBoundsInRoot.bottom
        }
        val distanceTop = listOf("mi", "km").minOf { label ->
            composeTestRule.onNodeWithText(label).fetchSemanticsNode().touchBoundsInRoot.top
        }
        assertTrue("Distance targets must sit below weight targets", distanceTop >= weightBottom)
        assertTouchContract()
    }

    @Test
    fun scrollingToThemeKeepsItsSegmentsInsideTheItem() {
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions()) }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Theme"))
        val themeItem = composeTestRule.onNodeWithText("Theme").fetchSemanticsNode().boundsInRoot
        listOf("System", "Dark", "Light").forEach { label ->
            val node = composeTestRule.onNodeWithText(label).fetchSemanticsNode()
            val bounds = node.touchBoundsInRoot
            assertEquals(node.size.height.toFloat(), node.boundsInRoot.height, 0.01f)
            assertTrue(
                "$label target $bounds must fit in Theme $themeItem",
                bounds.left >= themeItem.left && bounds.right <= themeItem.right &&
                    bounds.top >= themeItem.top && bounds.bottom <= themeItem.bottom,
            )
        }
        assertTouchContract()
    }

    @Test
    fun aPartlyScrolledDistanceChoiceKeepsItsTargetsApart() {
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions()) }
        }

        val list = composeTestRule.onNode(hasScrollAction())
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val target = composeTestRule.onNodeWithText("mi").fetchSemanticsNode().boundsInRoot
        val clippedPixels = with(composeTestRule.density) { 3.dp.toPx() }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            scrollBy(0f, target.top - viewport.top + clippedPixels)
        }

        listOf("mi", "km").forEach { label ->
            val node = composeTestRule.onNodeWithText(label).fetchSemanticsNode()
            assertTrue("$label must remain partly visible", !node.boundsInRoot.isEmpty)
            assertTrue("$label must be clipped", node.boundsInRoot.height < node.size.height)
        }
        assertTouchContract()
    }

    @Test
    fun backupHeaderKeepsItsTargetClear() {
        composeTestRule.setContent {
            AppTheme { BackupScreen(BackupUiState(), backupActions()) }
        }

        assertTouchContract()
    }

    @Test
    fun licensesHeaderKeepsItsTargetClear() {
        composeTestRule.setContent {
            AppTheme { LicensesScreen(entries = emptyList(), onBack = {}) }
        }

        assertTouchContract()
    }

    @Test
    fun logScreenWithoutBackArrowKeepsItsTargetsClear() {
        composeTestRule.setContent {
            AppTheme { LogScreen(LogUiState(), logActions()) }
        }

        assertTouchContract()
    }

    @Test
    fun navigationBarKeepsThreeClearTargets() {
        composeTestRule.setContent {
            AppTheme { AppNavigationBar(selectedRoute = Routes.TODAY, onSelect = {}) }
        }

        assertTouchContract()
    }

    @Test
    fun pickerBackKeepsIts32dpVisualInsideAClear48dpTarget() {
        composeTestRule.setContent {
            AppTheme {
                ExercisePickerScreen(
                    key = "touch-target",
                    title = "SWAP",
                    pattern = MovementPattern.SQUAT_BILATERAL,
                    candidates = ExerciseCatalog.CODE_ONLY.byPattern(MovementPattern.SQUAT_BILATERAL),
                    defaultEquipment = emptySet(),
                    accent = Color.White,
                    onPick = {},
                    onBack = {},
                    onCreateExercise = {},
                )
            }
        }

        assertTouchContract()
    }

    @Test
    fun customExerciseCloseKeepsItsTargetClear() {
        composeTestRule.setContent {
            AppTheme { CustomExerciseScreen(CustomExerciseUiState(), customExerciseActions()) }
        }

        assertTouchContract()
    }

    private fun assertTouchContract() {
        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
        composeTestRule.assertNoOverlappingTouchTargets()
    }

    private fun todayState() = TodayUiState(
        hasProgram = true,
        upNext = listOf(TodayDay(1, "Strength", "B", 1, "Lower body")),
        fact = "Last trained Oct 28",
    )

    private fun todayActions() = TodayActions(
        onStart = {},
        onOpenSettings = {},
        onSetUpProgram = {},
    )

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

    private fun backupActions() = BackupActions(
        onAutomaticBackupChange = {},
        onExportBackupClick = {}, onImportBackupClick = {}, onExportCsvClick = {}, onImportCsvClick = {},
        onConfirmRestore = {}, onCancelRestore = {}, onUnmatchedPatternChange = { _, _ -> },
        onConfirmCsvImport = {}, onCancelCsvImport = {}, onDismissMessage = {}, onBack = {},
    )

    private fun logActions() = LogActions(
        onToggleExpanded = {}, onPageCalendar = {}, onConnectHealth = {},
        onPublishPastWorkouts = {}, onApplyBodyweight = {}, onDismissBodyweight = {}, onShare = {},
        onStartSession = {}, onSetUpProgram = {},
    )

    private fun customExerciseActions() = CustomExerciseActions(
        onNameChange = {}, onPatternChange = {}, onEquipmentToggle = {}, onPerHandChange = {},
        onTrackingChange = {}, onWeightChange = {}, onTargetRepsChange = {}, onTargetSecondsChange = {},
        onAddedWeightChange = {}, onSave = {}, onCancel = {},
    )
}
