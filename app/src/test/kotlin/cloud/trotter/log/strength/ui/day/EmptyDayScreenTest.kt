package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayUiState
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
                dayEditState = DayEditUiState(authored = true),
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
    fun todayShowsTheEmptyDayTitleAndEmptyText() {
        rule.setContent { AppTheme {
            TodayScreen(
                TodayUiState(hasProgram = true, dayId = "A", dayTitle = "Daily rehab"),
                TodayActions(onStart = {}, onOpenSettings = {}, onOpenLog = {}, onSetUpProgram = {}),
            )
        } }
        rule.onNodeWithText("Daily rehab").assertExists()
        rule.onNodeWithText("No exercises yet").assertExists()
    }
}
