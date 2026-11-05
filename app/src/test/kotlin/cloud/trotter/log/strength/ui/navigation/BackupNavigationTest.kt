package cloud.trotter.log.strength.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.backup.BackupActions
import cloud.trotter.log.strength.ui.backup.BackupScreen
import cloud.trotter.log.strength.ui.backup.BackupUiState
import cloud.trotter.log.strength.ui.log.LogActions
import cloud.trotter.log.strength.ui.log.LogScreen
import cloud.trotter.log.strength.ui.log.LogUiState
import cloud.trotter.log.strength.ui.log.logSessionFixture
import cloud.trotter.log.strength.ui.setup.SetupActions
import cloud.trotter.log.strength.ui.setup.SetupScreen
import cloud.trotter.log.strength.ui.setup.SetupUiState
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class BackupNavigationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController

    private fun render(startDestination: String, history: LogUiState = LogUiState()) {
        rule.setContent { AppTheme {
            nav = rememberNavController()
            NavHost(nav, startDestination) {
                composable(Routes.SETUP) {
                    SetupScreen(SetupUiState(), SetupActions(
                        onUnitToggle = {}, onRestTimerEnabledChange = {},
                        onRestOverrideChange = { _, _ -> }, onRestOverridesReset = {},
                        onCreateCustomExercise = {}, onOpenLicenses = {},
                        onOpenBackup = { nav.navigate(Routes.BACKUP) },
                        onBack = { nav.popBackStack() },
                    ))
                }
                composable(Routes.LOG) {
                    LogScreen(history, LogActions(
                        onToggleExpanded = {}, onPageCalendar = {}, onConnectHealth = {},
                        onPublishPastWorkouts = {}, onApplyBodyweight = {}, onDismissBodyweight = {},
                        onShare = {}, onStartSession = {}, onSetUpProgram = {},
                        onOpenBackup = { nav.navigate(Routes.BACKUP) },
                    ))
                }
                composable(Routes.BACKUP) {
                    BackupScreen(BackupUiState(), BackupActions(
                        onAutomaticBackupChange = {}, onExportBackupClick = {}, onImportBackupClick = {},
                        onExportCsvClick = {}, onImportCsvClick = {}, onConfirmRestore = {}, onCancelRestore = {},
                        onUnmatchedPatternChange = { _, _ -> }, onConfirmCsvImport = {}, onCancelCsvImport = {},
                        onDismissMessage = {}, onBack = { nav.popBackStack() },
                    ))
                }
            }
        } }
    }

    private fun assertBackupScreen() {
        rule.runOnIdle { assertEquals(Routes.BACKUP, nav.currentDestination?.route) }
        rule.onNodeWithText("Backup").assertIsDisplayed()
        rule.onNodeWithText("Export backup").assertIsDisplayed()
    }

    @Test
    fun settingsBackupAndExportRowOpensBackupScreen() {
        render(Routes.SETUP)

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Backup and export"))
        rule.onNodeWithText("Backup and export").assertIsDisplayed().performClick()

        assertBackupScreen()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnIdle { assertEquals(Routes.SETUP, nav.currentDestination?.route) }
        rule.onNodeWithText("Backup and export").assertIsDisplayed()
    }

    @Test
    fun historyBackupAndExportMenuItemOpensBackupScreen() {
        render(Routes.LOG, LogUiState(sessions = listOf(logSessionFixture(false))))

        openBackupFromHistoryAndReturn()
    }

    @Test
    fun emptyHistoryBackupAndExportMenuItemOpensBackupScreen() {
        render(Routes.LOG, LogUiState(hasProgram = false))

        openBackupFromHistoryAndReturn()
    }

    private fun openBackupFromHistoryAndReturn() {
        rule.onNodeWithText("History").assertIsDisplayed()
        rule.onNodeWithText("Backup and export").assertDoesNotExist()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Backup and export").assertIsDisplayed().performClick()

        assertBackupScreen()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnIdle { assertEquals(Routes.LOG, nav.currentDestination?.route) }
        rule.onNodeWithText("History").assertIsDisplayed()
        rule.onNodeWithText("Backup and export").assertDoesNotExist()
    }
}
