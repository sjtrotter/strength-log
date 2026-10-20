package cloud.trotter.log.strength.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayLift
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen-level coverage for migrated chrome. These fixtures verify both halves
 * of #123's contract at the real call sites: every action owns at least 48dp,
 * and reserving that space never pushes its target onto a sibling.
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

        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
        composeTestRule.assertNoOverlappingTouchTargets()
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
        dayId = "B",
        dayIndex = 1,
        dayTitle = "Lower body",
        lifts = listOf(
            TodayLift("Barbell Back Squat", 5),
            TodayLift("Seated Leg Curl", 3),
        ),
        lifeLine = UiText.TodayHistoryLife(daysAgo = 2, monthSessions = 3),
        actionLabel = cloud.trotter.log.strength.ui.today.TodayScreenBuilder.actionLabel("B", 0, 18),
    )

    private fun todayActions() = TodayActions(
        onStart = {},
        onOpenSettings = {},
        onOpenLog = {},
        onSetUpProgram = {},
    )

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
