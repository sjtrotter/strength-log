package cloud.trotter.log.strength.ui.day

import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TimedRepScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun card(): ExerciseCardState {
        val rows = List(2) { SetRowState(it, "Set ${it + 1}", false, 0.0, 0, false) }
        return pagerFixture().exercises[1].copy(
            title = "Push-ups", exerciseId = "pushup", isMain = false,
            headerTarget = HeaderTarget.Prescribed("2:00", repAim = 40),
            tracking = TrackingType.REPS, timedRepDurationSeconds = 120, timedRepAim = 40,
            rows = rows, focusIndex = 0, focusLabel = UiText.SetLabel(SetKind.WORK, 1),
            nextSetDisplay = null,
        )
    }

    private fun event(awaiting: Boolean = false) = TimedRepEvent(
        "D", card().programExerciseId, "pushup", 0, 120, 1_000L, 1_000L, 1, awaitingCount = awaiting,
    )

    private fun state() = pagerFixture().copy(exercises = listOf(card()))

    @Test fun pageShowsFullClockRunningClockEntryAndCompletedActualCount() {
        var state by mutableStateOf(state())
        rule.setContent { AppTheme { DayScreen(state, pagerActions(), DayEditUiState(), pagerEditActions()) } }
        rule.onNodeWithText("Target 2:00 · aim 40").assertExists()
        rule.onNodeWithText("2:00").assertExists()
        rule.onNodeWithText("Start").assertIsEnabled()
        rule.runOnIdle { state = state.copy(timedRep = TimedRepState(event(), 119), executionBusy = true) }
        rule.onNodeWithText("1:59").assertExists()
        rule.onNodeWithText("Stop").assertExists()
        rule.runOnIdle { state = state.copy(timedRep = TimedRepState(event(awaiting = true), 0), executionBusy = false) }
        rule.onNodeWithText("0:00").assertExists()
        rule.onNodeWithText("Enter reps").assertExists()
        rule.runOnIdle {
            val completed = card().copy(
                rows = card().rows.map { it.copy(done = true, reps = 38) },
                allDone = true, focusIndex = -1,
            )
            state = state.copy(exercises = listOf(completed), timedRep = null)
        }
        rule.onNodeWithText("38 reps").assertExists()
        rule.onNodeWithText("38 / 40 reps").assertExists()
        rule.onNodeWithText("0:00").assertDoesNotExist()
    }

    @Test fun stopReturnsThePageToFullDurationAndNextSetStartsAtFullDuration() {
        var state by mutableStateOf(state().copy(timedRep = TimedRepState(event(), 42), executionBusy = true))
        val actions = pagerActions().copy(onStopTimedRep = { state = state.copy(timedRep = null, executionBusy = false) })
        rule.setContent { AppTheme { DayScreen(state, actions, DayEditUiState(), pagerEditActions()) } }
        rule.onNodeWithText("Stop").performClick()
        rule.onNodeWithText("2:00").assertExists()
        rule.onNodeWithText("Start").assertIsEnabled()
        rule.runOnIdle {
            val next = card().copy(rows = card().rows.map { if (it.index == 0) it.copy(done = true, reps = 38) else it },
                focusIndex = 1, focusLabel = UiText.SetLabel(SetKind.WORK, 2))
            state = state.copy(exercises = listOf(next))
        }
        rule.onNodeWithText("2:00").assertExists()
        rule.onNodeWithText("Set 2 of 2").assertExists()
    }

    @Test fun uncheckingEarlierSetKeepsEventFocusThroughExpiryDismissalAndLogging() {
        val second = card().copy(
            rows = card().rows.map { if (it.index == 0) it.copy(done = true, reps = 38) else it },
            focusIndex = 1, focusLabel = UiText.SetLabel(SetKind.WORK, 2),
        )
        var state by mutableStateOf(state().copy(exercises = listOf(second)))
        val actions = pagerActions().copy(
            onStartTimedRep = { _, index ->
                state = state.copy(
                    timedRep = TimedRepState(event().copy(setIndex = index), 119), executionBusy = true,
                )
            },
            onOpenTimedRepCount = {
                state = state.copy(
                    timedRep = state.timedRep?.let { it.copy(event = it.event.copy(sheetRequested = true)) },
                    openSheet = OpenSheet("D", OpenSheet.Kind.TIMED_REP_COUNT, card().programExerciseId, 1),
                )
            },
            onCloseSetEditor = {
                state = state.copy(
                    openSheet = null,
                    timedRep = state.timedRep?.let { it.copy(event = it.event.copy(sheetDismissed = true)) },
                )
            },
            onRequestTimedRepCount = {
                state = state.copy(timedRep = state.timedRep?.let {
                    it.copy(event = it.event.copy(sheetRequested = false, sheetDismissed = false))
                })
            },
            onTimedRepDraftChange = { draft ->
                state = state.copy(timedRep = state.timedRep?.let { it.copy(event = it.event.copy(draft = draft)) })
            },
            onLogTimedRepCount = {
                val event = requireNotNull(state.timedRep).event
                val current = state.exercises.single()
                state = state.copy(
                    exercises = listOf(current.copy(rows = current.rows.map {
                        if (it.index == event.setIndex) it.copy(done = true, reps = requireNotNull(event.count)) else it
                    })),
                    timedRep = null, openSheet = null,
                )
            },
        )
        rule.setContent { AppTheme { DayScreen(state, actions, DayEditUiState(), pagerEditActions()) } }
        rule.onNodeWithText("Start").performClick()
        rule.runOnIdle { state = state.copy(exercises = listOf(card())) }
        rule.onNodeWithText("Set 2").assertExists()
        rule.onNodeWithText("Set 2 of 2").assertExists()
        rule.onNodeWithText("1:59").assertExists()
        rule.onNodeWithText("Stop").assertIsEnabled()
        rule.onNodeWithText("Start").assertDoesNotExist()
        rule.runOnIdle {
            state = state.copy(
                timedRep = TimedRepState(event(awaiting = true).copy(setIndex = 1), 0), executionBusy = false,
            )
        }
        rule.onNodeWithText("Push-ups · Set 2").assertExists()
        rule.runOnIdle { actions.onCloseSetEditor() }
        rule.onNodeWithText("Time’s up").assertDoesNotExist()
        rule.onNodeWithText("Set 2 of 2").assertExists()
        rule.onNodeWithText("0:00").assertExists()
        rule.onNodeWithText("Enter reps").assertIsEnabled().performClick()
        rule.onNodeWithText("Push-ups · Set 2").assertExists()
        rule.onNodeWithText("Reps").performTextReplacement("38")
        rule.onNodeWithText("Log 38 reps").performClick()
        rule.runOnIdle {
            val rows = state.exercises.single().rows
            assertFalse(rows[0].done)
            assertTrue(rows[1].done)
            assertEquals(38, rows[1].reps)
        }
        rule.onNodeWithText("Set 1").assertExists()
        rule.onNodeWithText("Set 1 of 2").assertExists()
        rule.onNodeWithText("2:00").assertExists()
        rule.onNodeWithText("Start").assertIsEnabled().performClick()
        rule.onNodeWithText("Stop").assertIsEnabled()
        rule.runOnIdle { assertEquals(0, state.timedRep?.event?.setIndex) }
    }

    @Test fun stoppingEventAfterEarlierSetIsUncheckedRestoresNormalFocus() {
        var state by mutableStateOf(state().copy(
            timedRep = TimedRepState(event().copy(setIndex = 1), 42), executionBusy = true,
        ))
        val actions = pagerActions().copy(onStopTimedRep = { state = state.copy(timedRep = null, executionBusy = false) })
        rule.setContent { AppTheme { DayScreen(state, actions, DayEditUiState(), pagerEditActions()) } }
        rule.onNodeWithText("Set 2").assertExists()
        rule.onNodeWithText("Set 2 of 2").assertExists()
        rule.onNodeWithText("0:42").assertExists()
        rule.onNodeWithText("Stop").assertIsEnabled().performClick()
        rule.onNodeWithText("Set 1").assertExists()
        rule.onNodeWithText("Set 1 of 2").assertExists()
        rule.onNodeWithText("2:00").assertExists()
        rule.onNodeWithText("Start").assertIsEnabled()
    }

    @Test fun countControlsStartAtZeroAllowZeroDisableBlankAndShowTheAim() {
        var event by mutableStateOf(event(awaiting = true))
        val logged = mutableListOf<Int>()
        val actions = pagerActions().copy(
            onTimedRepDraftChange = { event = event.copy(draft = it) },
            onLogTimedRepCount = { event.count?.let { logged += it } },
        )
        rule.setContent { AppTheme { TimedRepCountSheet(card(), event, 3, actions) } }
        rule.onNodeWithText("Time’s up").assertExists()
        rule.onNodeWithText("Push-ups · Set 1").assertExists()
        rule.onNodeWithText("Reps").assertIsNotFocused()
        rule.onNodeWithText("0 / 40 reps").assertExists()
        rule.onNodeWithText("Log 0 reps").assertIsEnabled().performClick()
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
        rule.setContent { AppTheme {
            TimedRepCountSheet(card().copy(isSuperset = true), event(awaiting = true).copy(draft = "38"), 3,
                pagerActions().copy(onLogTimedRepCount = { logged++ }))
        } }
        rule.onNodeWithText("Log Push-ups 38 reps").assertIsEnabled()
        rule.onNodeWithContentDescription("Log Push-ups 38 reps").performClick()
        assertEquals(1, logged)
    }

    @Test fun entryWithoutAimOmitsTheComparison() {
        rule.setContent { AppTheme {
            TimedRepCountSheet(card().copy(timedRepAim = null), event(awaiting = true).copy(draft = "38"), 3, pagerActions())
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
        val actions = pagerActions().copy(
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
        rule.setContent { AppTheme { DayScreen(state, actions, DayEditUiState(), pagerEditActions()) } }
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

    @Test fun temporaryScreenHoldCoversOnlyTheRunningClockAndClearsOnDisposal() {
        var state by mutableStateOf(state())
        var visible by mutableStateOf(true)
        var view: View? = null
        rule.setContent {
            val currentView = LocalView.current
            SideEffect { view = currentView }
            if (visible) AppTheme { DayScreen(state, pagerActions(), DayEditUiState(), pagerEditActions()) }
        }
        rule.runOnIdle { assertFalse(requireNotNull(view).keepScreenOn) }
        rule.runOnIdle { state = state.copy(timedRep = TimedRepState(event(), 120)) }
        rule.runOnIdle { assertTrue(requireNotNull(view).keepScreenOn) }
        rule.runOnIdle { state = state.copy(timedRep = TimedRepState(event(awaiting = true), 0)) }
        rule.runOnIdle { assertFalse(requireNotNull(view).keepScreenOn) }
        rule.runOnIdle { state = state.copy(timedRep = TimedRepState(event(), 120)) }
        rule.runOnIdle { assertTrue(requireNotNull(view).keepScreenOn) }
        rule.runOnIdle { visible = false }
        rule.runOnIdle { assertFalse(requireNotNull(view).keepScreenOn) }
    }

    @Test fun timedRepStartIsDisabledWhileAnotherExecutionRuns() {
        rule.setContent { AppTheme {
            DayScreen(state().copy(executionBusy = true), pagerActions(), DayEditUiState(), pagerEditActions())
        } }
        rule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test fun cardioStartIsDisabledWhileTimedRepClockRuns() {
        rule.setContent { AppTheme {
            DayScreen(
                state().copy(
                    cardio = cardioFixture(),
                    timedRep = TimedRepState(event(), 120), executionBusy = true,
                ), pagerActions(), DayEditUiState(), pagerEditActions(), standaloneCardio = true,
            )
        } }
        rule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test fun laterEditorEditsOnlyRepsAndAllowsZero() {
        val card = card()
        var row by mutableStateOf(card.rows[0].copy(done = true, reps = 38))
        val edits = mutableListOf<Int>()
        val actions = pagerActions().copy(onRepsChange = { _, _, _, count ->
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
