package cloud.trotter.log.strength.audit

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.transfer.health.ExternalSessionRow
import cloud.trotter.log.strength.ui.log.BackfillOfferUi
import cloud.trotter.log.strength.ui.log.BodyweightPromptUi
import cloud.trotter.log.strength.ui.log.HealthSectionUi
import cloud.trotter.log.strength.ui.log.LogActions
import cloud.trotter.log.strength.ui.log.LogScreen
import cloud.trotter.log.strength.ui.log.LogScreenBuilder
import cloud.trotter.log.strength.ui.log.LogUiState
import cloud.trotter.log.strength.ui.log.logSessionFixture
import cloud.trotter.log.strength.ui.navigation.TopLevelDestination
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditHistoryShotsTest : UiAuditTest() {

    @Test fun shot01HistoryEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/01-history-empty") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(), logActions()) } }
    }

    @Test fun shot02HistoryOne() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/02-history-one") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(logSessionFixture(false))), logActions()) } }
    }

    @Test fun shot03HistoryFull() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/03-history-full") { AuditDestination(TopLevelDestination.LOG) { LogScreen(auditHistoryFixture(), logActions()) } }
    }

    @Test fun shot04HistoryRowExpanded() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = mutableStateOf(LogUiState(sessions = listOf(logSessionFixture(false))))
        rule.auditShot("history/04-history-row-expanded", beforeCapture = {
            rule.onNodeWithText("DAY A · LOWER").performClick()
            rule.mainClock.advanceTimeBy(300)
        }) {
            AuditDestination(TopLevelDestination.LOG) {
                LogScreen(state.value, logActions().copy(onToggleExpanded = {
                    state.value = state.value.copy(sessions = listOf(logSessionFixture(true)))
                }))
            }
        }
    }

    @Test fun shot05HistoryCardio() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/05-history-cardio") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(logSessionFixture(false).copy(cardioId = 8, dayTitle = "Easy run", cardioSummary = "Road run", cardioDuration = "20:00", cardioSemantics = "Easy run", cardioSeconds = 1200))), logActions()) } }
    }

    @Test fun shot06HistoryLong() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/06-history-long") { AuditDestination(TopLevelDestination.LOG) { LogScreen(auditHistoryFixture().let { it.copy(sessions = it.sessions.map { row -> row.copy(dayTitle = LONG_DAY, planName = LONG_PLAN) }, journal = it.journal.copy(trajectories = it.journal.trajectories.map { card -> card.copy(exerciseName = longestExercise.name) })) }, logActions()) } }
    }

    @Test fun shot07HistoryFullLight() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/07-history-full-light", theme = ThemePreference.LIGHT) { AuditDestination(TopLevelDestination.LOG) { LogScreen(auditHistoryFixture(), logActions()) } }
    }

    @Config(fontScale = 2f)
    @Test fun shot08HistoryFullFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/08-history-full-font2", fontScale = 2f) { AuditDestination(TopLevelDestination.LOG) { LogScreen(auditHistoryFixture(), logActions()) } }
    }

    @Test fun shot09HistoryNoProgram() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/09-history-no-program") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(hasProgram = false), logActions()) } }
    }

    @Test fun shot10HistoryHealthDisconnected() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/10-history-health-disconnected") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(logSessionFixture(false)), health = HealthSectionUi(available = true)), logActions()) } }
    }

    @Test fun shot11HistoryHealthPublishing() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/11-history-health-publishing") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(logSessionFixture(false)), health = HealthSectionUi(available = true, publishing = true)), logActions()) } }
    }

    @Test fun shot12HistoryHealthBackfill() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/12-history-health-backfill") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(logSessionFixture(false)), health = HealthSectionUi(available = true, publishing = true, backfill = BackfillOfferUi(LogScreenBuilder.backfillLabel(12, false), true))), logActions()) } }
    }

    @Test fun shot13HistoryHealthBackfillRunning() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/13-history-health-backfill-running") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(logSessionFixture(false)), health = HealthSectionUi(available = true, publishing = true, backfill = BackfillOfferUi(LogScreenBuilder.backfillLabel(12, true), false))), logActions()) } }
    }

    @Test fun shot14HistoryBodyweightPrompt() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/14-history-bodyweight-prompt") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(logSessionFixture(false)), health = HealthSectionUi(available = true, publishing = true, bodyweightPrompt = BodyweightPromptUi("185", "190"))), logActions()) } }
    }

    @Test fun shot15HistoryRowLoading() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/15-history-row-loading") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(logSessionFixture(false).copy(expanded = true))), logActions()) } }
    }

    @Test fun shot16HistoryRowEditing() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/16-history-row-editing") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(editableSession().copy(editing = true))), logActions()) } }
    }

    @Test fun shot17HistoryRowUndo() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/17-history-row-undo") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(editableSession().copy(editing = true, undoPending = true))), logActions()) } }
    }

    @Test fun shot18HistoryVolume() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/18-history-volume", beforeCapture = {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("VOLUME"))
        }) { AuditDestination(TopLevelDestination.LOG) { LogScreen(auditHistoryFixture(), logActions()) } }
    }

    @Test fun shot19HistoryCalendar() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/19-history-calendar", beforeCapture = {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("NOVEMBER 2026"))
        }) { AuditDestination(TopLevelDestination.LOG) { LogScreen(auditHistoryFixture(), logActions()) } }
    }

    @Test fun shot20HistoryUnplotted() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/20-history-unplotted") { AuditDestination(TopLevelDestination.LOG) { LogScreen(auditHistoryFixture().let { it.copy(journal = it.journal.copy(trajectories = it.journal.trajectories.map { card -> card.copy(points = card.points.take(1)) })) }, logActions()) } }
    }

    @Test fun shot21HistoryExternal() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/21-history-external") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(health = HealthSectionUi(available = true, externalSessions = listOf(ExternalSessionRow("Afternoon strength", "Nov 3, 2026", "Training app")))), logActions()) } }
    }

    @Test fun shot22HistoryDeleteConfirm() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/22-history-delete-confirm", beforeCapture = {
            rule.onNodeWithText("DELETE SESSION").performScrollTo().performClick()
            rule.mainClock.advanceTimeBy(300)
        }) { AuditDestination(TopLevelDestination.LOG) {
            LogScreen(LogUiState(sessions = listOf(editableSession().copy(editing = true))), logActions())
        } }
    }

    @Test fun shot23HistoryNoBodyweight() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/23-history-no-bodyweight") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(logSessionFixture(false).copy(bodyweightDisplay = null))), logActions()) } }
    }

    @Test fun shot24HistoryExpandedEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("history/24-history-expanded-empty") { AuditDestination(TopLevelDestination.LOG) { LogScreen(LogUiState(sessions = listOf(logSessionFixture(true).copy(exerciseGroups = emptyList()))), logActions()) } }
    }

    private fun logActions() = LogActions(onToggleExpanded = {}, onPageCalendar = {}, onConnectHealth = {},
        onPublishPastWorkouts = {}, onApplyBodyweight = {}, onDismissBodyweight = {}, onShare = {},
        onStartSession = {}, onSetUpProgram = {})

    /** The moved fixture carries display text only; editing reads the canonical fields. */
    private fun editableSession() = logSessionFixture(true).let { session ->
        session.copy(exerciseGroups = session.exerciseGroups?.map { group ->
            group.copy(sets = group.sets.map { set -> set.copy(id = 1L, weightLb = 225.0, reps = 5, done = true) })
        })
    }
}
