package cloud.trotter.log.strength.ui.preview

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.components.LOADING_REVEAL_DELAY_MS
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

    @Test fun absentAndIncomparableHistoryHaveDistinctMessages() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, lifts = listOf(
                PreviewLift("Plank", "45 s", listOf(PreviewPreviousResult("45 s"))),
                PreviewLift("Push-up", "12 reps", listOf(PreviewPreviousResult(null))),
                PreviewLift("Side plank", "30 s", listOf(PreviewPreviousResult(null, comparable = false))),
            ),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Last 45 s").assertExists()
        rule.onNodeWithText("No previous result").assertExists()
        rule.onNodeWithText("No comparable previous result").assertExists()
    }

    @Test fun loadingKeepsStartAndEditHidden() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            selection = DaySelection(1, "A"),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Start day A").assertDoesNotExist()
        rule.onNodeWithText("Edit").assertDoesNotExist()
        rule.onNodeWithText("No lifts in this day yet").assertDoesNotExist()
        rule.onNodeWithText("Edit day").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(LOADING_REVEAL_DELAY_MS - 60)
        rule.onNodeWithText("Loading…").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(120)
        rule.onNodeWithText("Loading…").assertExists()
    }

    @Test fun liftsShowPerformedNamesWorkingLoadLastAndSuperset() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, selection = DaySelection(1, "A"), planName = "Strength", title = "Upper",
            lifts = listOf(PreviewLift("Bench + Row", "135 lb × 5 + 95 lb × 8", listOf(
                PreviewPreviousResult("130 lb × 5"), PreviewPreviousResult("90 lb × 8"),
            ))),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Bench + Row").assertExists()
        rule.onNodeWithText("Today 135 lb × 5 + 95 lb × 8").assertExists()
        rule.onNodeWithText("Last 130 lb × 5").assertExists()
        rule.onNodeWithText("Last 90 lb × 8").assertExists()
    }

    @Test fun supersetKeepsComparableHistoryBesideAPartnerWithoutHistory() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, lifts = listOf(PreviewLift("Bench + Row", null, listOf(
                PreviewPreviousResult("130 lb × 5"), PreviewPreviousResult(null),
            ))),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Last 130 lb × 5").assertExists()
        rule.onNodeWithText("No previous result").assertExists()
    }

    @Test fun supersetKeepsComparableHistoryBesideAMismatchedPartner() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, lifts = listOf(PreviewLift("Bench + Row", null, listOf(
                PreviewPreviousResult("130 lb × 5"), PreviewPreviousResult(null, comparable = false),
            ))),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Last 130 lb × 5").assertExists()
        rule.onNodeWithText("No comparable previous result").assertExists()
    }

    @Test fun emptyDayOffersEditAndNoStart() {
        var edited = false
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, selection = DaySelection(1, "A"), title = "Rehab",
        ), PreviewActions({}, { edited = true }, {})) } }
        rule.onNodeWithText("No lifts in this day yet").assertExists()
        rule.onNodeWithText("Start day A").assertDoesNotExist()
        rule.onNodeWithText("Edit day").performClick()
        assertTrue(edited)
    }

    @Test fun startAndEditForwardTheirOwnActions() {
        var started = false
        var edited = false
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, selection = DaySelection(1, "B"), title = "Upper",
            lifts = listOf(PreviewLift("Bench", "135 lb × 5", listOf(PreviewPreviousResult(null)))),
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
        rule.onNodeWithText("No lifts in this day yet").assertDoesNotExist()
    }
}
