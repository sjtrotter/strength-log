package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.preview.PreviewActions
import cloud.trotter.log.strength.ui.preview.PreviewScreen
import cloud.trotter.log.strength.ui.preview.PreviewUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class EmptyDayPreviewTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun previewShowsTheEmptyDayTitleAndEditInsteadOfStart() {
        rule.setContent { AppTheme {
            PreviewScreen(
                PreviewUiState(loading = false, selection = DaySelection(1, "A"), title = "Daily rehab"),
                PreviewActions(onStart = {}, onBack = {}, onEdit = {}),
            )
        } }
        rule.onNodeWithText("Daily rehab").assertExists()
        rule.onNodeWithText("No lifts yet").assertExists()
        rule.onNodeWithText("Edit").assertExists()
        rule.onNodeWithText("Start day A").assertDoesNotExist()
    }
}
