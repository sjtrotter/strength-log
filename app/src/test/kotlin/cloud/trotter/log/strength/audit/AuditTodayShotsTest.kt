package cloud.trotter.log.strength.audit

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.navigation.TopLevelDestination
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayUiState
import cloud.trotter.log.strength.ui.today.todaySecondFixture
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditTodayShotsTest : UiAuditTest() {

    @Test fun shot01TodayFirstLaunch() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/01-today-first-launch") { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(TodayUiState(), TodayActions({}, {}, {})) } }
    }

    @Test fun shot02TodayReady() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/02-today-ready") { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(auditTodayFixture(), TodayActions({}, {}, {})) } }
    }

    @Test fun shot03TodayInProgress() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/03-today-in-progress") { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(auditTodayFixture().copy(resume = todaySecondFixture()), TodayActions({}, {}, {})) } }
    }

    @Test fun shot04TodayDoneMorePlans() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/04-today-done-more-plans") { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(auditTodayFixture().copy(upNext = listOf(todaySecondFixture()), fact = "Nov 3"), TodayActions({}, {}, {})) } }
    }

    @Test fun shot05TodayAllPlansDone() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/05-today-all-plans-done") { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(auditTodayFixture().copy(upNext = emptyList(), nothingOnToday = true, fact = "Nov 3"), TodayActions({}, {}, {})) } }
    }

    @Test fun shot06TodayLong() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/06-today-long") { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(auditTodayFixture().let { it.copy(upNext = it.upNext.map { day -> day.copy(planName = LONG_PLAN, title = LONG_DAY) }) }, TodayActions({}, {}, {})) } }
    }

    @Test fun shot07TodayReadyLight() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/07-today-ready-light", theme = ThemePreference.LIGHT) { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(auditTodayFixture(), TodayActions({}, {}, {})) } }
    }

    @Config(fontScale = 2f)
    @Test fun shot08TodayReadyFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/08-today-ready-font2", fontScale = 2f) { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(auditTodayFixture(), TodayActions({}, {}, {})) } }
    }

    @Test fun shot09TodayLoading() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/09-today-loading") { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(TodayUiState(loading = true), TodayActions({}, {}, {})) } }
    }

    @Test fun shot10TodayPaused() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/10-today-paused") { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(TodayUiState(hasProgram = true, nothingOnToday = true, plansPaused = true), TodayActions({}, {}, {})) } }
    }

    @Test fun shot11TodayPausedResume() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/11-today-paused-resume") { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(TodayUiState(hasProgram = true, nothingOnToday = true, plansPaused = true, resume = todaySecondFixture()), TodayActions({}, {}, {})) } }
    }

    @Test fun shot12TodayOtherDays() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = mutableStateOf(auditTodayFixture())
        rule.auditShot("today/12-today-other-days", beforeCapture = {
            rule.onNodeWithText("Other days").performClick()
            rule.mainClock.advanceTimeBy(300)
        }) {
            AuditDestination(TopLevelDestination.TODAY) {
                TodayScreen(state.value, TodayActions({}, {}, {}, onToggleOtherDays = {
                    state.value = state.value.copy(otherDaysExpanded = true)
                }))
            }
        }
    }

    @Test fun shot13TodayCardio() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("today/13-today-cardio") { AuditDestination(TopLevelDestination.TODAY) { TodayScreen(auditTodayFixture().copy(upNext = listOf(todaySecondFixture().copy(cardioOnly = true, title = "Easy run"))), TodayActions({}, {}, {})) } }
    }

}
