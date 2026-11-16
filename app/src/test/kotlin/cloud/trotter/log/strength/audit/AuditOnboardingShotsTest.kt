package cloud.trotter.log.strength.audit

import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.ui.setup.SetupActions
import cloud.trotter.log.strength.ui.setup.SetupScreen
import cloud.trotter.log.strength.ui.setup.SetupUiState
import cloud.trotter.log.strength.ui.wizard.SetupDraft
import cloud.trotter.log.strength.ui.wizard.SetupFlowActions
import cloud.trotter.log.strength.ui.wizard.SetupFlowFixtures
import cloud.trotter.log.strength.ui.wizard.SetupFlowScreen
import cloud.trotter.log.strength.ui.wizard.SetupStep
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

    @Test fun shot09Setup() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/09-setup") { SetupScreen(SetupUiState(), SetupActions(onUnitToggle = {},
            onRestTimerEnabledChange = {}, onRestOverrideChange = { _, _ -> }, onRestOverridesReset = {},
            onCreateCustomExercise = {}, onOpenBackup = {}, onOpenLicenses = {}, onBack = {})) }
    }

    @Test fun shot17SetupRestDisabled() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/17-setup-rest-disabled") { SetupScreen(SetupUiState(restTimerEnabled = false, phoneRestTimerEnabled = false),
            SetupActions(onUnitToggle = {}, onRestTimerEnabledChange = {}, onRestOverrideChange = { _, _ -> },
                onRestOverridesReset = {}, onCreateCustomExercise = {}, onOpenBackup = {}, onOpenLicenses = {}, onBack = {}),
            openRestTimer = true) }
    }

    @Test fun shot30SetupGoal() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/30-setup-goal") {
            SetupFlowScreen(SetupFlowFixtures.state(SetupDraft(), firstRun = true), SetupFlowActions())
        }
    }

    @Test fun shot31SetupWeek() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/31-setup-week") {
            SetupFlowScreen(SetupFlowFixtures.state(SetupDraft(step = SetupStep.WEEK), firstRun = false), SetupFlowActions())
        }
    }

    @Test fun shot32SetupEquipmentExact() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/32-setup-equipment-exact") {
            SetupFlowScreen(SetupFlowFixtures.state(SetupDraft(step = SetupStep.EQUIPMENT, equipment = EquipmentPreset.DUMBBELLS.equipment, chooseExactly = true), firstRun = false), SetupFlowActions())
        }
    }

    @Test fun shot33SetupAbout() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/33-setup-about") {
            SetupFlowScreen(SetupFlowFixtures.state(SetupDraft(step = SetupStep.ABOUT), firstRun = false), SetupFlowActions())
        }
    }

    @Test fun shot34SetupRecent() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/34-setup-recent") {
            SetupFlowScreen(SetupFlowFixtures.state(SetupFlowFixtures.reference.copy(step = SetupStep.RECENT), firstRun = false), SetupFlowActions())
        }
    }

    @Test fun shot35SetupPlan() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/35-setup-plan") {
            SetupFlowScreen(SetupFlowFixtures.state(SetupFlowFixtures.reference.copy(step = SetupStep.PLAN), firstRun = false), SetupFlowActions())
        }
    }

    @Test fun shot36SetupPlanOverBudget() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("onboarding/36-setup-plan-over-budget") {
            SetupFlowScreen(SetupFlowFixtures.state(SetupFlowFixtures.overBudget.copy(step = SetupStep.PLAN), firstRun = false), SetupFlowActions())
        }
    }

}
