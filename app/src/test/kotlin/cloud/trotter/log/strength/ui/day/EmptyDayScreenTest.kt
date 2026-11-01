package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.preview.PreviewActions
import cloud.trotter.log.strength.ui.preview.PreviewScreen
import cloud.trotter.log.strength.ui.preview.PreviewUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class EmptyDayScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun emptyDayShowsItsTitleAndKeepsEditReachableWithoutAPager() {
        var opened = false
        rule.setContent { AppTheme {
            DayScreen(
                state = DayUiState(hasProgram = true, viewDayId = "A", dayTitle = "Daily rehab"),
                actions = pagerActions(),
                dayEditState = DayEditUiState(),
                dayEditActions = pagerEditActions().copy(onEditorOpen = { opened = it }),
            )
        } }
        rule.onNodeWithText("Daily rehab").assertExists()
        rule.onNodeWithText("No exercises yet").assertExists()
        rule.onNodeWithText("Edit day").performClick()
        assertTrue(opened)
        rule.onNodeWithText("Lift 1 of 0").assertDoesNotExist()
    }

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
