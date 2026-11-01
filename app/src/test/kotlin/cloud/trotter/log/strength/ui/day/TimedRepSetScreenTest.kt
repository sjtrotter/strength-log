package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TimedRepSetScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun card(): ExerciseCardState {
        val rows = List(2) { SetRowState(it, "Set ${it + 1}", false, 0.0, 0, false) }
        return workoutSetTimedRepFixture().exercises.single().copy(
            title = "Push-ups", exerciseId = "pushup", isMain = false,
            headerTarget = HeaderTarget.Prescribed("2:00", repAim = 40),
            tracking = TrackingType.REPS, timedRepDurationSeconds = 120, timedRepAim = 40,
            rows = rows, focusIndex = 0,
        )
    }

    private fun event(awaiting: Boolean = false) = TimedRepEvent(
        "D", card().programExerciseId, "pushup", 0, 120, 1_000L, 1_000L, 1, awaitingCount = awaiting,
    )

    private fun state() = workoutSetTimedRepFixture().copy(viewDayId = "D", exercises = listOf(card())).withWorkoutSelection()

    @Test fun countControlsStartAtZeroAllowZeroDisableBlankAndShowTheAim() {
        var event by mutableStateOf(event(awaiting = true))
        val logged = mutableListOf<Int>()
        val actions = workoutActions().copy(
            onTimedRepDraftChange = { event = event.copy(draft = it) },
            onLogTimedRepCount = { event.count?.let { logged += it } },
        )
        rule.setContent { AppTheme { TimedRepCountSheet(card(), event, 3, actions) } }
        rule.onNodeWithText("Time’s up").assertExists()
        rule.onNodeWithText("Push-ups · Set 1").assertExists()
        rule.onNodeWithText("Reps").assertIsNotFocused()
        rule.onNodeWithText("0 / 40 reps").assertExists()
        rule.onNodeWithText("Log 0 reps").assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)).performClick()
        rule.onNodeWithText("Reps").performTextReplacement("1")
        rule.onNodeWithText("Log 1 rep").assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        rule.onNodeWithText("Log 1 reps").assertDoesNotExist()
        rule.onNodeWithText("Reps").performTextReplacement("")
        rule.onNodeWithText("Log 0 reps").assertIsNotEnabled()
        rule.onNodeWithText("Reps").performTextReplacement("38")
        rule.onNodeWithText("38 / 40 reps").assertExists()
        rule.onNodeWithText("Log 38 reps").performClick()
        rule.onNodeWithContentDescription("Reps, one less").performClick()
        rule.onNodeWithText("Log 37 reps").assertIsEnabled()
        rule.onNodeWithContentDescription("Reps, one more").performClick()
        rule.onNodeWithText("Log 38 reps").assertIsEnabled()
        assertEquals(listOf(0, 38), logged)
    }

    @Test fun supersetCountConfirmationNamesTheMainExercise() {
        var logged = 0
        var countEvent by mutableStateOf(event(awaiting = true).copy(draft = "1"))
        rule.setContent { AppTheme {
            TimedRepCountSheet(card().copy(isSuperset = true), countEvent, 3,
                workoutActions().copy(onLogTimedRepCount = { logged++ }))
        } }
        rule.onNodeWithText("Log Push-ups 1 rep").assertIsEnabled()
        rule.onNodeWithContentDescription("Log Push-ups 1 rep").assertExists()
        rule.runOnIdle { countEvent = countEvent.copy(draft = "38") }
        rule.onNodeWithText("Log Push-ups 38 reps").assertIsEnabled()
        rule.onNodeWithContentDescription("Log Push-ups 38 reps").performClick()
        assertEquals(1, logged)
    }

    @Test fun entryWithoutAimOmitsTheComparison() {
        rule.setContent { AppTheme {
            TimedRepCountSheet(card().copy(timedRepAim = null), event(awaiting = true).copy(draft = "38"), 3, workoutActions())
        } }
        rule.onNodeWithText("38 / 40 reps").assertDoesNotExist()
        rule.onNodeWithText("Log 38 reps").assertIsEnabled()
    }

    @Test fun screenQueuesAutomaticEntryBehindAnotherSheetAndDoesNotRequestItAgain() {
        var state by mutableStateOf(state().copy(
            timedRep = TimedRepState(event(awaiting = true), 0),
            openSheet = OpenSheet("D", OpenSheet.Kind.EDITOR, card().programExerciseId, 1),
        ))
        var requests = 0
        val actions = workoutActions().copy(
            onCloseSetEditor = {
                state = state.copy(openSheet = null, timedRep = state.timedRep?.let {
                    if (it.event.sheetRequested) it.copy(event = it.event.copy(sheetDismissed = true)) else it
                })
            },
            onOpenTimedRepCount = {
                requests++
                state = state.copy(
                    timedRep = state.timedRep?.let { it.copy(event = it.event.copy(sheetRequested = true)) },
                    openSheet = OpenSheet("D", OpenSheet.Kind.TIMED_REP_COUNT, card().programExerciseId, 0),
                )
            },
        )
        rule.setContent { AppTheme { WorkoutSetHost(state.withWorkoutSelection(), actions, DayEditUiState(), workoutEditActions()) } }
        rule.runOnIdle { assertEquals(0, requests) }
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Time’s up").assertExists()
        rule.runOnIdle { assertEquals(1, requests) }
        rule.runOnIdle { state = state.copy(watchStatus = WatchStatus(WatchStatusKind.ACTIVE)) }
        rule.runOnIdle { assertEquals(1, requests) }
        rule.runOnIdle { actions.onCloseSetEditor() }
        rule.onNodeWithText("Enter reps").assertExists()
        rule.onNodeWithText("Time’s up").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun laterEditorEditsOnlyRepsAndAllowsZero() {
        val card = card()
        var row by mutableStateOf(card.rows[0].copy(done = true, reps = 38))
        val edits = mutableListOf<Int>()
        val actions = workoutActions().copy(onRepsChange = { _, _, _, count ->
            edits += count
            row = row.copy(reps = count)
        })
        rule.setContent { AppTheme { SetEditSheet(card, row, WeightUnit.LB, 3, actions, {}) } }
        rule.onNodeWithText("Weight, lb").assertDoesNotExist()
        rule.onNodeWithText("Seconds").assertDoesNotExist()
        rule.onNodeWithText("38 / 40 reps").assertExists()
        rule.onNodeWithText("Reps").performTextReplacement("0")
        rule.onNodeWithText("0 / 40 reps").assertExists()
        rule.onNodeWithText("Done").assertExists()
        rule.runOnIdle { assertEquals(listOf(0), edits) }
    }
}
