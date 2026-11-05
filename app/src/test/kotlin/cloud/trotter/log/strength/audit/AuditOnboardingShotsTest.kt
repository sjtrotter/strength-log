package cloud.trotter.log.strength.audit

import cloud.trotter.log.strength.ui.setup.SetupActions
import cloud.trotter.log.strength.ui.setup.SetupScreen
import cloud.trotter.log.strength.ui.setup.SetupUiState
import cloud.trotter.log.strength.ui.text.BackupErrorKind
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.wizard.WizardActions
import cloud.trotter.log.strength.ui.wizard.WizardRestoreState
import cloud.trotter.log.strength.ui.wizard.WizardScreen
import cloud.trotter.log.strength.ui.wizard.WizardStep
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditOnboardingShotsTest : UiAuditTest() {

    @Test fun shot01WizardEmphasis() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/01-wizard-emphasis") { WizardScreen(auditWizardFixture(WizardStep.EMPHASIS), wizardActions()) }
    }

    @Test fun shot02WizardDaysPerWeek() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/02-wizard-days-per-week") { WizardScreen(auditWizardFixture(WizardStep.DAYS_PER_WEEK), wizardActions()) }
    }

    @Test fun shot03WizardSplit() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/03-wizard-split") { WizardScreen(auditWizardFixture(WizardStep.SPLIT), wizardActions()) }
    }

    @Test fun shot04WizardAnchors() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/04-wizard-anchors") { WizardScreen(auditWizardFixture(WizardStep.ANCHORS), wizardActions()) }
    }

    @Test fun shot05WizardCardio() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/05-wizard-cardio") { WizardScreen(auditWizardFixture(WizardStep.CARDIO), wizardActions()) }
    }

    @Test fun shot06WizardAboutYou() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/06-wizard-about-you") { WizardScreen(auditWizardFixture(WizardStep.ABOUT_YOU), wizardActions()) }
    }

    @Test fun shot07WizardEquipment() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/07-wizard-equipment") { WizardScreen(auditWizardFixture(WizardStep.EQUIPMENT), wizardActions()) }
    }

    @Test fun shot08WizardRotation() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/08-wizard-rotation") { WizardScreen(auditWizardFixture(WizardStep.ROTATION), wizardActions()) }
    }

    @Test fun shot09Setup() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/09-setup") { SetupScreen(SetupUiState(), SetupActions(onUnitToggle = {},
            onRestTimerEnabledChange = {}, onRestOverrideChange = { _, _ -> }, onRestOverridesReset = {},
            onCreateCustomExercise = {}, onOpenBackup = {}, onOpenLicenses = {}, onBack = {})) }
    }

    @Config(fontScale = 2f)
    @Test fun shot10WizardEmphasisFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/10-wizard-emphasis-font2", fontScale = 2f) { WizardScreen(auditWizardFixture(WizardStep.EMPHASIS), wizardActions()) }
    }

    @Test fun shot11WizardRestoreOffered() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/11-wizard-restore-offered") { WizardScreen(auditWizardFixture(WizardStep.EMPHASIS).copy(restore = WizardRestoreState(offered = true)), wizardActions()) }
    }

    @Test fun shot12WizardRestoreRunning() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/12-wizard-restore-running") { WizardScreen(auditWizardFixture(WizardStep.EMPHASIS).copy(restore = WizardRestoreState(offered = true, inFlight = true)), wizardActions()) }
    }

    @Test fun shot13WizardRestoreFailed() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/13-wizard-restore-failed") { WizardScreen(auditWizardFixture(WizardStep.EMPHASIS).copy(restore = WizardRestoreState(offered = true, error = UiText.BackupError(BackupErrorKind.BACKUP_MALFORMED))), wizardActions()) }
    }

    @Test fun shot14WizardFinishRunning() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/14-wizard-finish-running") { WizardScreen(auditWizardFixture(WizardStep.ROTATION).copy(finishInFlight = true), wizardActions()) }
    }

    @Test fun shot15WizardFinishFailed() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/15-wizard-finish-failed") { WizardScreen(auditWizardFixture(WizardStep.ROTATION).copy(finishError = UiText.FileAccessFailure("Storage is full")), wizardActions()) }
    }

    @Test fun shot16WizardRotationEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/16-wizard-rotation-empty") { WizardScreen(auditWizardFixture(WizardStep.ROTATION).copy(previewProgram = null), wizardActions()) }
    }

    @Test fun shot17SetupRestDisabled() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/17-setup-rest-disabled") { SetupScreen(SetupUiState(restTimerEnabled = false, phoneRestTimerEnabled = false),
            SetupActions(onUnitToggle = {}, onRestTimerEnabledChange = {}, onRestOverrideChange = { _, _ -> },
                onRestOverridesReset = {}, onCreateCustomExercise = {}, onOpenBackup = {}, onOpenLicenses = {}, onBack = {}),
            openRestTimer = true) }
    }

    private fun wizardActions() = WizardActions(onNext = {}, onBack = {}, onEmphasisChange = {},
        onDaysPerWeekChange = {}, onSplitChange = {}, onAnchorSchemeChange = {}, onDeadliftVariantChange = {},
        onCardioModeChange = {}, onCardioPlacementChange = {}, onFiveKChange = {}, onBodyweightChange = {},
        onUnitChange = {}, onAgeChange = {}, onLevelChange = {}, onEquipmentToggle = {}, onRestoreFromBackup = {})

}
