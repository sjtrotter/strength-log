package cloud.trotter.log.strength.ui.backup

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #172: a restore writes Room and then DataStore with nothing spanning the two,
 * and the easy way to land between them was to press back. The write is app-
 * scoped now, but the screen still has to hold still while it runs — otherwise
 * the result lands on a screen nobody is looking at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class BackupBusyGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var backs = 0

    private fun setContent(busy: Boolean) {
        composeTestRule.setContent {
            AppTheme {
                BackupScreen(state = BackupUiState(isBusy = busy), actions = actions())
            }
        }
    }

    private fun actions() = BackupActions(
        onExportBackupClick = {},
        onImportBackupClick = {},
        onExportCsvClick = {},
        onImportCsvClick = {},
        onConfirmRestore = {},
        onCancelRestore = {},
        onUnmatchedPatternChange = { _: String, _: MovementPattern -> },
        onConfirmCsvImport = {},
        onCancelCsvImport = {},
        onDismissMessage = {},
        onBack = { backs++ },
    )

    @Test
    fun theBackChevronIsDeadWhileAnImportIsInFlight() {
        setContent(busy = true)

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals("back must not fire mid-restore", 0, backs)
    }

    @Test
    fun theBackChevronWorksWhenNothingIsRunning() {
        setContent(busy = false)

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }
}
