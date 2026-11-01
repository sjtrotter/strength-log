package cloud.trotter.log.strength.ui.today

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayScreenTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test fun nothingOnTodayOpensPlans() {
        var opened = false
        composeTestRule.setContent { AppTheme {
            TodayScreen(TodayUiState(nothingOnToday = true), TodayActions(
                onStart = {}, onOpenSettings = {}, onOpenLog = {}, onSetUpProgram = {},
                onOpenPlans = { opened = true },
            ))
        } }
        composeTestRule.onNodeWithText("Nothing on Today").assertExists()
        composeTestRule.onNodeWithText("Plans").performClick()
        assertTrue(opened)
    }
}
