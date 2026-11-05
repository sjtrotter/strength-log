package cloud.trotter.log.strength.audit

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.backup.AutomaticBackupUiState
import cloud.trotter.log.strength.ui.backup.BackupActions
import cloud.trotter.log.strength.ui.backup.BackupOperation
import cloud.trotter.log.strength.ui.backup.BackupScreen
import cloud.trotter.log.strength.ui.backup.BackupUiState
import cloud.trotter.log.strength.ui.backup.CsvImportUiState
import cloud.trotter.log.strength.ui.backup.StatusMessage
import cloud.trotter.log.strength.ui.backup.csvPreviewFixture
import cloud.trotter.log.strength.ui.customexercise.CustomExerciseActions
import cloud.trotter.log.strength.ui.customexercise.CustomExerciseScreen
import cloud.trotter.log.strength.ui.customexercise.CustomExerciseUiState
import cloud.trotter.log.strength.ui.licenses.LicenseEntry
import cloud.trotter.log.strength.ui.licenses.LicensesScreen
import cloud.trotter.log.strength.ui.text.BackupErrorKind
import cloud.trotter.log.strength.ui.text.BackupStatusKind
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.you.YouActions
import cloud.trotter.log.strength.ui.you.YouScreen
import cloud.trotter.log.strength.ui.you.YouUiState
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditSettingsShotsTest : UiAuditTest() {

    @Test fun shot01You() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/01-you") { YouScreen(YouUiState(), YouActions({}, {}, {}, {})) }
    }

    @Test fun shot02BackupIdle() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/02-backup-idle") { BackupScreen(BackupUiState(), backupActions()) }
    }

    @Test fun shot03BackupAutomaticOn() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/03-backup-automatic-on") { BackupScreen(BackupUiState(automatic = AutomaticBackupUiState(enabled = true, folderName = "Training backups", lastSuccessAtMillis = 1_000)), backupActions()) }
    }

    @Test fun shot04BackupCsvRunning() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/04-backup-csv-running", beforeCapture = { rule.onNode(hasScrollAction()).performScrollToNode(hasText("CSV history")) }) { BackupScreen(BackupUiState(operation = BackupOperation.ExportCsv), backupActions()) }
    }

    @Test fun shot05BackupCsvFailed() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/05-backup-csv-failed", beforeCapture = { rule.onNode(hasScrollAction()).performScrollToNode(hasText("truncated or corrupt", substring = true)) }) { BackupScreen(BackupUiState(message = StatusMessage(UiText.BackupError(BackupErrorKind.CSV_MALFORMED), true)), backupActions()) }
    }

    @Test fun shot06BackupCsvFinished() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/06-backup-csv-finished", beforeCapture = { rule.onNode(hasScrollAction()).performScrollToNode(hasText("Imported 3 session(s), 18 set(s).")) }) { BackupScreen(BackupUiState(message = StatusMessage(UiText.BackupStatus(BackupStatusKind.HISTORY_IMPORTED, 3, 18), false)), backupActions()) }
    }

    @Test fun shot07Licenses() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entries = listOf(
            LicenseEntry("Barlow Condensed — SIL Open Font License 1.1", context.assets.open("licenses/barlow-condensed-OFL.txt").bufferedReader().use { it.readText() }),
            LicenseEntry("Third-party libraries — Apache License 2.0", context.assets.open("licenses/apache-2.0-notices.txt").bufferedReader().use { it.readText() }),
        )
        rule.auditShot("settings/07-licenses") { LicensesScreen(entries, {}) }
    }

    @Test fun shot08CustomEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/08-custom-empty") { CustomExerciseScreen(CustomExerciseUiState(), customActions()) }
    }

    @Test fun shot09CustomFilled() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/09-custom-filled") { CustomExerciseScreen(CustomExerciseUiState(name = "Seated cable shoulder press", pattern = cloud.trotter.log.strength.domain.model.MovementPattern.V_PUSH, equipment = setOf(Equipment.CABLE)), customActions()) }
    }

    // Whitespace-only names show validation copy and keep Save disabled.
    @Test fun shot10CustomValidationDisabled() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/10-custom-validation-disabled") { CustomExerciseScreen(CustomExerciseUiState(name = "   "), customActions()) }
    }

    @Test fun shot11YouLight() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/11-you-light", theme = ThemePreference.LIGHT) { YouScreen(YouUiState(), YouActions({}, {}, {}, {})) }
    }

    @Config(fontScale = 2f)
    @Test fun shot12YouFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/12-you-font2", fontScale = 2f) { YouScreen(YouUiState(), YouActions({}, {}, {}, {})) }
    }

    @Test fun shot13BackupRestoreRunning() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/13-backup-restore-running") { BackupScreen(BackupUiState(operation = BackupOperation.Restore, restoreInFlight = true), backupActions()) }
    }

    @Test fun shot14BackupRestoreConfirm() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/14-backup-restore-confirm") { BackupScreen(BackupUiState(pendingRestoreConfirm = true), backupActions()) }
    }

    @Test fun shot15BackupCsvPreview() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/15-backup-csv-preview") { BackupScreen(BackupUiState(csvImport = CsvImportUiState.of(csvPreviewFixture())), backupActions()) }
    }

    @Test fun shot16BackupCsvUnapproved() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/16-backup-csv-unapproved") { BackupScreen(BackupUiState(csvImport = CsvImportUiState(csvPreviewFixture(), emptyMap())), backupActions()) }
    }

    @Test fun shot17BackupFolderUnavailable() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/17-backup-folder-unavailable") { BackupScreen(BackupUiState(automatic = AutomaticBackupUiState(enabled = true, folderName = "Training backups", permissionLost = true)), backupActions()) }
    }

    @Test fun shot18BackupAutomaticFailed() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/18-backup-automatic-failed") { BackupScreen(BackupUiState(automatic = AutomaticBackupUiState(enabled = true, folderName = "Training backups", lastAttemptFailed = true)), backupActions()) }
    }

    @Test fun shot19CustomReps() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/19-custom-reps") { CustomExerciseScreen(CustomExerciseUiState(name = "Bench step-up", pattern = cloud.trotter.log.strength.domain.model.MovementPattern.SINGLE_LEG, tracking = TrackingType.REPS), customActions()) }
    }

    @Test fun shot20CustomHold() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("settings/20-custom-hold") { CustomExerciseScreen(CustomExerciseUiState(name = "Loaded wall hold", pattern = cloud.trotter.log.strength.domain.model.MovementPattern.CORE_ANTI_EXT, tracking = TrackingType.TIMED, addedWeightDisplay = 25.0), customActions()) }
    }

    private fun backupActions() = BackupActions(onAutomaticBackupChange = {}, onExportBackupClick = {},
        onImportBackupClick = {}, onExportCsvClick = {}, onImportCsvClick = {}, onConfirmRestore = {},
        onCancelRestore = {}, onUnmatchedPatternChange = { _, _ -> }, onConfirmCsvImport = {},
        onCancelCsvImport = {}, onDismissMessage = {}, onBack = {})
    private fun customActions() = CustomExerciseActions(onNameChange = {}, onPatternChange = {},
        onEquipmentToggle = {}, onPerHandChange = {}, onTrackingChange = {}, onWeightChange = {},
        onTargetRepsChange = {}, onTargetSecondsChange = {}, onAddedWeightChange = {}, onSave = {}, onCancel = {})

}
