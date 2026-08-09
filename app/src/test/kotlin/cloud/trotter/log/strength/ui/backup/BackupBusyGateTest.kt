package cloud.trotter.log.strength.ui.backup

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
 * and the easy way to land between them was to press back. The write is
 * app-scoped now, but the screen still has to hold still while it runs —
 * otherwise the result lands on a screen nobody is looking at.
 *
 * The gate is [BackupUiState.restoreInFlight], deliberately not the generic
 * `isBusy`: an export or a CSV preview has nothing to tear, and refusing back
 * during those would be a regression, so both are pinned here.
 *
 * System back is fired through the activity's real dispatcher rather than
 * asserted on the chevron alone — the chevron and the hardware/gesture back are
 * two different exits, and the bug only needs one of them left open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class BackupBusyGateTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var backs = 0

    /** A back callback standing in for whatever is behind this screen (the nav
     *  host). Registered *before* the screen composes, so the screen's own
     *  handler — registered after — takes priority exactly as it does in the
     *  app; this one only fires on a press the screen declined. */
    private class Fallback : OnBackPressedCallback(true) {
        var fired = 0
        override fun handleOnBackPressed() {
            fired++
        }
    }

    private fun registerFallback(): Fallback {
        val callback = Fallback()
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.addCallback(callback)
        }
        return callback
    }

    private fun setContent(state: BackupUiState) {
        composeTestRule.setContent {
            AppTheme { BackupScreen(state = state, actions = actions()) }
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

    private fun pressSystemBack() {
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun theBackChevronIsDeadWhileARestoreIsInFlight() {
        setContent(BackupUiState(isBusy = true, restoreInFlight = true))

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals("back must not fire mid-restore", 0, backs)
    }

    @Test
    fun systemBackIsSwallowedWhileARestoreIsInFlight() {
        val fallback = registerFallback()
        setContent(BackupUiState(isBusy = true, restoreInFlight = true))

        pressSystemBack()

        assertEquals("the screen has to consume system back too", 0, fallback.fired)
        assertEquals(0, backs)
    }

    @Test
    fun systemBackWorksOnceTheRestoreIsDone() {
        val fallback = registerFallback()
        setContent(BackupUiState())

        pressSystemBack()

        assertEquals("nothing is running, so back belongs to the app again", 1, fallback.fired)
    }

    @Test
    fun anExportDoesNotTrapTheUserOnTheScreen() {
        // isBusy without restoreInFlight: an export is running. Nothing it writes
        // can be torn by leaving, so both exits stay open.
        val fallback = registerFallback()
        setContent(BackupUiState(isBusy = true))

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        pressSystemBack()

        assertEquals(1, backs)
        assertEquals(1, fallback.fired)
    }
}
