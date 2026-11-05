package cloud.trotter.log.strength.ui.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class BackupOperationStatesTest {
    @get:Rule val rule = createComposeRule()

    private fun actions() = BackupActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})

    private fun running(operation: BackupOperation, label: String, section: String) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { BackupScreen(BackupUiState(operation = operation), actions()) } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(section))
        rule.mainClock.advanceTimeBy(400)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        rule.onNodeWithText(label).assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test fun exportingBackupHasItsOwnLabel() = running(BackupOperation.ExportBackup, "Exporting backup…", "Full backup")
    @Test fun exportingCsvHasItsOwnLabel() = running(BackupOperation.ExportCsv, "Exporting CSV…", "CSV history")
    @Test fun restoringHasItsOwnLabel() = running(BackupOperation.Restore, "Restoring backup. Keep this screen open.", "Full backup")
    @Test fun importingCsvHasItsOwnLabel() = running(BackupOperation.ImportCsv, "Importing workouts…", "CSV history")

    @Test fun csvImportNeedsEveryConfirmationAndShowsTheConfirmedRow() {
        var state by mutableStateOf(BackupUiState(csvImport = CsvImportUiState.of(csvPreviewFixture())))
        rule.setContent { AppTheme { BackupScreen(state, actions().copy(onUnmatchedPatternChange = { name, pattern ->
            state = state.copy(csvImport = state.csvImport!!.let { it.copy(approvedPatterns = it.approvedPatterns + (name to pattern)) })
        })) } }
        rule.onNodeWithText("Import").assertIsNotEnabled()
        rule.onNodeWithText("Confirm a movement for each exercise above.").assertIsDisplayed()
        rule.onNodeWithText("Confirm").performClick()
        rule.onNodeWithText("Confirmed").assertIsDisplayed()
        rule.onNodeWithText("Import").assertIsEnabled()
        rule.onNodeWithText("Confirm a movement for each exercise above.").assertDoesNotExist()
    }

    @Test fun unavailableFolderUsesTheExistingPickerHandler() {
        var picked = 0
        rule.setContent { AppTheme { BackupScreen(BackupUiState(automatic = AutomaticBackupUiState(
            enabled = true, permissionLost = true,
        )), actions().copy(onAutomaticBackupChange = { if (it) picked++ })) } }
        rule.onNodeWithText("Backup folder is unavailable.").assertIsDisplayed()
        rule.onNodeWithText("Choose folder").performClick()
        assertEquals(1, picked)
    }

    @Test fun automaticBackupFailureShowsNoInventedReasonOrRetryPromise() {
        rule.setContent { AppTheme { BackupScreen(BackupUiState(automatic = AutomaticBackupUiState(
            enabled = true, lastAttemptFailed = true,
        )), actions()) } }
        rule.onNodeWithText("Automatic backup failed.").assertIsDisplayed()
        rule.onNodeWithText("No successful backup yet").assertIsDisplayed()
    }

    @Test fun failedAutomaticBackupStillShowsTheLastSuccessfulDate() {
        rule.setContent { AppTheme { BackupScreen(BackupUiState(automatic = AutomaticBackupUiState(
            enabled = true, lastAttemptFailed = true, lastSuccessAtMillis = 1_000L,
        )), actions()) } }
        rule.onNodeWithText("Automatic backup failed.").assertIsDisplayed()
        rule.onNodeWithText("Last successful backup:", substring = true).assertIsDisplayed()
        rule.onNodeWithText("No successful backup yet").assertDoesNotExist()
    }

    @Test fun restoreFreezesAutomaticBackupSwitch() {
        var changes = 0
        rule.setContent { AppTheme { BackupScreen(BackupUiState(operation = BackupOperation.Restore, restoreInFlight = true),
            actions().copy(onAutomaticBackupChange = { changes++ })) } }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch))
            .assertIsNotEnabled().performClick()
        assertEquals(0, changes)
    }
}
