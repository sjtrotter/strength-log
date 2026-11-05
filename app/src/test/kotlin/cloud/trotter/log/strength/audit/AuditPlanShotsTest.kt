package cloud.trotter.log.strength.audit

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import cloud.trotter.log.strength.data.PlanSummary
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.navigation.TopLevelDestination
import cloud.trotter.log.strength.ui.plan.PlanActions
import cloud.trotter.log.strength.ui.plan.PlanDay
import cloud.trotter.log.strength.ui.plan.PlanScreen
import cloud.trotter.log.strength.ui.plan.PlanUiState
import cloud.trotter.log.strength.ui.plan.PlansActions
import cloud.trotter.log.strength.ui.plan.PlansScreen
import cloud.trotter.log.strength.ui.plan.PlansUiState
import cloud.trotter.log.strength.ui.plan.YouSummary
import cloud.trotter.log.strength.ui.plan.planScreenFixture
import cloud.trotter.log.strength.ui.setup.GoalPreviewItem
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditPlanShotsTest : UiAuditTest() {

    @Test fun shot01PlansOne() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/01-plans-one") { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(1), plansActions()) } }
    }

    @Test fun shot02PlansSeveral() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/02-plans-several") { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(4), plansActions()) } }
    }

    @Test fun shot03PlansEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/03-plans-empty") { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(PlansUiState(), plansActions()) } }
    }

    @Test fun shot04PlanStrength() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/04-plan-strength") { PlanScreen(planScreenFixture(4).let { it.copy(name = "Four-day strength", days = it.days.map { day -> day.copy(liftCount = 4, setCount = 18) }) }, PlanActions({}, {}, {})) }
    }

    @Test fun shot05PlanCardio() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/05-plan-cardio") { PlanScreen(planScreenFixture().copy(name = "5K build", days = listOf(PlanDay("C1", 0, "Easy run", 0, 0, true, CardioActivity.ROAD_RUN, 5, ProgramDayKind.CARDIO))), PlanActions({}, {}, {})) }
    }

    @Test fun shot06PlansLong() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/06-plans-long") { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(3).let { it.copy(plans = it.plans.map { plan -> plan.copy(name = LONG_PLAN, nextDayTitle = LONG_DAY) }) }, plansActions()) } }
    }

    @Test fun shot07PlansSeveralLight() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/07-plans-several-light", theme = ThemePreference.LIGHT) { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(4), plansActions()) } }
    }

    @Test fun shot08PlanLoading() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/08-plan-loading") { PlanScreen(PlanUiState(loading = true), PlanActions({}, {}, {})) }
    }

    @Test fun shot09PlanNoProgram() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/09-plan-no-program") { PlanScreen(PlanUiState(), PlanActions({}, {}, {})) }
    }

    @Test fun shot10PlanEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/10-plan-empty") { PlanScreen(PlanUiState(name = "New plan", hasProgram = true), PlanActions({}, {}, {})) }
    }

    @Test fun shot11PlansMenu() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/11-plans-menu") { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(2).copy(menuPlanId = 1), plansActions()) } }
    }

    @Test fun shot12PlansRename() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/12-plans-rename", manualClock = true) { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(2).copy(renamePlanId = 1, renameDraft = LONG_PLAN), plansActions()) } }
    }

    @Test fun shot13PlansNewChoice() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/13-plans-new-choice") { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(1).copy(newPlanPage = "choice"), plansActions()) } }
    }

    @Test fun shot14PlansNewEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/14-plans-new-empty", manualClock = true) { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(1).copy(newPlanPage = "name"), plansActions()) } }
    }

    @Test fun shot15PlansCreating() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/15-plans-creating", manualClock = true) { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(1).copy(newPlanPage = "name", newPlanName = LONG_PLAN, creating = true), plansActions()) } }
    }

    @Test fun shot16PlansArchived() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/16-plans-archived") { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(1).let { it.copy(plans = it.plans.map { plan -> plan.copy(archived = true, selected = false, onToday = false) }) }, plansActions()) } }
    }

    @Test fun shot17PlansPaused() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/17-plans-paused") { AuditDestination(TopLevelDestination.PLANS) { PlansScreen(plansFixture(1).let { it.copy(plans = it.plans.map { plan -> plan.copy(selected = false, onToday = false) }) }, plansActions()) } }
    }

    @Test fun shot18PlanDayMenu() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/18-plan-day-menu") { PlanScreen(planScreenFixture(2, menu = "A"), PlanActions({}, {}, {})) }
    }

    @Test fun shot19PlanDayRename() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/19-plan-day-rename", manualClock = true) { PlanScreen(planScreenFixture().copy(dialog = "rename", dialogDayId = "A", dayTitleDraft = LONG_DAY), PlanActions({}, {}, {})) }
    }

    @Test fun shot20PlanDayRemove() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/20-plan-day-remove") { PlanScreen(planScreenFixture(2).copy(dialog = "remove", dialogDayId = "B", dayTitleDraft = "Day B"), PlanActions({}, {}, {})) }
    }

    @Test fun shot21PlanLong() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/21-plan-long") { PlanScreen(planScreenFixture(4).let { it.copy(name = LONG_PLAN, days = it.days.map { day -> day.copy(title = LONG_DAY) }) }, PlanActions({}, {}, {})) }
    }

    @Test fun shot22PlanStrengthLight() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/22-plan-strength-light", theme = ThemePreference.LIGHT) { PlanScreen(planScreenFixture(4).let { it.copy(name = "Four-day strength", days = it.days.map { day -> day.copy(liftCount = 4, setCount = 18) }) }, PlanActions({}, {}, {})) }
    }

    @Test fun shot23PlanGeneratedGoals() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/23-plan-generated-goals", beforeCapture = {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Goals"))
        }) { PlanScreen(planScreenFixture().copy(emphasis = GoalEmphasis.BALANCED,
            goals = listOf(GoalPreviewItem("Barbell Back Squat", "235", false))), PlanActions({}, {}, {})) }
    }

    @Test fun shot24PlanRenameEnabled() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("plan/24-plan-rename-enabled", manualClock = true) { PlanScreen(planScreenFixture().copy(dialog = "rename", dialogDayId = "A", dayTitleDraft = "Lower body"), PlanActions({}, {}, {})) }
    }

    private fun plansFixture(count: Int) = PlansUiState(
        plans = List(count) { index -> PlanSummary(index + 1L, "Strength rotation ${index + 1}", "A", "Squat focus",
            index == 0, false, null, onToday = index < 2) },
        you = YouSummary("185 lb", 35, "intermediate"),
    )
    private fun plansActions() = PlansActions(onNewPlan = {}, onSelect = {}, onMenu = {}, onRename = {},
        onRenameDraft = {}, onSaveRename = {}, onDismissRename = {}, onDuplicate = {}, onArchive = {}, onRestore = {})

}
