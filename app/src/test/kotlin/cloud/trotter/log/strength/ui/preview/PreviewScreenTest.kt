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

    @Test fun noHistoryOmitsTheLastLine() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, lifts = listOf(PreviewLift("Plank", "3 × 0:45", listOf(PreviewPreviousResult(null)))),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Today 3 × 0:45").assertExists()
        rule.onNodeWithText("Last", substring = true).assertDoesNotExist()
        rule.onNodeWithText("No previous result").assertDoesNotExist()
    }

    @Test fun fallbackLabelsTheDayAndPlanAndNextTimeSitsBesideToday() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, lifts = listOf(
                PreviewLift("Squat", "TOP × 5 · 235 lb", listOf(
                    PreviewPreviousResult("235 lb × 5", source = "B"),
                ), nextTime = "+5 lb"),
                PreviewLift("Bench", "3 × 5 · 70 lb", listOf(
                    PreviewPreviousResult("70 lb × 5", source = "C", sourcePlanName = "Strength"),
                ), nextTime = "Repeat"),
            ),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Today TOP × 5 · 235 lb").assertExists()
        rule.onNodeWithText("+5 lb").assertExists()
        rule.onNodeWithText("Repeat").assertExists()
        rule.onNodeWithText("Last 235 lb × 5 · Day B").assertExists()
        rule.onNodeWithText("Last 70 lb × 5 · Strength · Day C").assertExists()
    }

    @Test fun absentHistoryHasNoLastLineAndIncomparableHistoryKeepsItsMessage() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, lifts = listOf(
                PreviewLift("Plank", "3 × 0:45", listOf(PreviewPreviousResult("45 s"))),
                PreviewLift("Push-up", "3 × 12", listOf(PreviewPreviousResult(null))),
                PreviewLift("Side plank", "3 × 0:30", listOf(PreviewPreviousResult(null, comparable = false))),
            ),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Last 45 s").assertExists()
        rule.onNodeWithText("No previous result").assertDoesNotExist()
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
            lifts = listOf(PreviewLift("Bench + Row", "3 × 5 · 135 lb + 3 × 8 · 95 lb", listOf(
                PreviewPreviousResult("130 lb × 5", exerciseName = "Bench"), PreviewPreviousResult("90 lb × 8", exerciseName = "Row"),
            ))),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Bench + Row").assertExists()
        rule.onNodeWithText("Today 3 × 5 · 135 lb + 3 × 8 · 95 lb").assertExists()
        rule.onNodeWithText("Bench: Last 130 lb × 5").assertExists()
        rule.onNodeWithText("Row: Last 90 lb × 8").assertExists()
    }

    @Test fun supersetKeepsComparableHistoryBesideAPartnerWithoutHistory() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, lifts = listOf(PreviewLift("Bench + Row", null, listOf(
                PreviewPreviousResult("130 lb × 5", exerciseName = "Bench"), PreviewPreviousResult(null, exerciseName = "Row"),
            ))),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Bench: Last 130 lb × 5").assertExists()
        rule.onNodeWithText("Row:", substring = true).assertDoesNotExist()
    }

    @Test fun supersetKeepsComparableHistoryBesideAMismatchedPartner() {
        rule.setContent { AppTheme { PreviewScreen(PreviewUiState(
            loading = false, lifts = listOf(PreviewLift("Bench + Row", null, listOf(
                PreviewPreviousResult("130 lb × 5", exerciseName = "Bench"), PreviewPreviousResult(null, comparable = false, exerciseName = "Row"),
            ))),
        ), PreviewActions({}, {}, {})) } }
        rule.onNodeWithText("Bench: Last 130 lb × 5").assertExists()
        rule.onNodeWithText("Row: No comparable previous result").assertExists()
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
            lifts = listOf(PreviewLift("Bench", "3 × 5 · 135 lb", listOf(PreviewPreviousResult(null)))),
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
