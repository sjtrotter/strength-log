package cloud.trotter.log.strength.ui.preview

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class PreviewScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun loadingKeepsStartAndEditHidden() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            selection = DaySelection(1, "A"),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Start day A").assertDoesNotExist()
        rule.onNodeWithText("Edit").assertDoesNotExist()
        rule.onNodeWithText("No lifts yet").assertDoesNotExist()
    }

    @Test fun liftsShowPerformedNamesWorkingLoadLastAndSuperset() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, selection = DaySelection(1, "A"), planName = "Strength", title = "Upper",
            lifts = listOf(PreviewLift("Bench + Row", "135 × 5 + 95 × 8", "130 × 5 + 90 × 8")),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Bench + Row").assertExists()
        rule.onNodeWithText("Today 135 × 5 + 95 × 8").assertExists()
        rule.onNodeWithText("Last 130 × 5 + 90 × 8").assertExists()
    }

    @Test fun emptyDayOffersEditAndNoStart() {
        var edited = false
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, selection = DaySelection(1, "A"), title = "Rehab",
        ), PreviewActions({}, { edited = true }, {})) } }
        rule.onNodeWithText("No lifts yet").assertExists()
        rule.onNodeWithText("Start day A").assertDoesNotExist()
        rule.onNodeWithText("Edit").performClick()
        assertTrue(edited)
    }

    @Test fun startAndEditForwardTheirOwnActions() {
        var started = false
        var edited = false
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, selection = DaySelection(1, "B"), title = "Upper",
            lifts = listOf(PreviewLift("Bench", "135 × 5", null)),
        ), PreviewActions({}, { edited = true }, { started = true })) } }
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Start day B").performClick()
        assertTrue(edited)
        assertTrue(started)
    }

    @Test fun cardioOnlyDayShowsActivityTimeAndStart() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, selection = DaySelection(1, "C1"), title = "Cardio",
            cardio = PreviewCardio("Road run", "20:00"),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Road run").assertExists()
        rule.onNodeWithText("20:00").assertExists()
        rule.onNodeWithText("Start day C1").assertExists()
        rule.onNodeWithText("No lifts yet").assertDoesNotExist()
    }
}
