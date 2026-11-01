package cloud.trotter.log.strength.ui.day

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rules retained from the retired workout UI, exercised through the set screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class WorkoutSetMigrationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun shorterPartnerTrackStillLogsTheUnfinishedHalf() {
        val base = workoutSetSupersetFixture()
        val card = base.exercises.single().copy(rows = listOf(
            SetRowState(0, "1", false, 50.0, 10, true, PartnerRowState(90.0, 12)),
            SetRowState(1, "2", false, 50.0, 10, true),
        ))
        val state = base.copy(exercises = listOf(card)).withWorkoutSelection()
        val writes = mutableListOf<Pair<Int, String>>()
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(
            onLogFocusedSet = { state.workout?.focus?.let { writes += it.index to it.slot } },
        )) } }
        rule.onNodeWithText("Log 90 × 12").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf(0 to Slot.SS), writes) }
    }

    @Test fun mainDoneWithPartnerOpenRequiresFinishConfirmation() {
        val base = workoutSetSupersetFixture()
        val card = base.exercises.single().copy(rows = listOf(
            SetRowState(0, "1", false, 50.0, 10, true, PartnerRowState(90.0, 12)),
        ))
        val state = base.copy(exercises = listOf(card)).withWorkoutSelection()
        var finishes = 0
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(onDone = { finishes++ })) } }
        rule.runOnIdle { assertEquals(state.totalSets, state.doneSets) }
        rule.onNodeWithText("Finish").performClick()
        rule.onNodeWithText("Finish with partner sets open?").assertExists()
        rule.runOnIdle { assertEquals(0, finishes) }
        rule.onNodeWithText("Finish").performClick()
        rule.runOnIdle { assertEquals(1, finishes) }
    }

    @Test fun finishAcceptsOnlyPartnerWorkIncludingRetiredWork() {
        var retired by mutableStateOf(false)
        val base = workoutSetSupersetFixture()
        rule.setContent { AppTheme {
            val card = base.exercises.single().copy(rows = listOf(
                SetRowState(0, "1", false, 50.0, 10, false, PartnerRowState(90.0, 12, done = !retired)),
            ))
            WorkoutSetHost(base.copy(exercises = listOf(card),
                retiredTracks = if (retired) listOf(RetiredTrackLine("Seated Cable Row", 1)) else emptyList(),
            ).withWorkoutSelection())
        } }
        rule.onNodeWithText("Finish").assertIsEnabled()
        rule.runOnIdle { retired = true }
        rule.onNodeWithText("Finish").assertIsEnabled()
    }

    @Test fun blankResistanceAddsNoAuthoredDetail() {
        val base = workoutSetSingleFixture()
        val card = base.exercises.single().copy(
            plateLine = null, headerTarget = HeaderTarget.Prescribed("3 × 12", resistance = "  "),
        )
        rule.setContent { AppTheme { WorkoutSetHost(base.copy(exercises = listOf(card))) } }
        rule.onNodeWithText("Each side", substring = true).assertDoesNotExist()
        rule.onNodeWithText("  ").assertDoesNotExist()
    }

    @Test fun plainRepsShowsCountWithoutWeight() {
        val base = workoutSetTimedRepFixture()
        val card = base.exercises.single().copy(timedRepDurationSeconds = null, timedRepAim = null,
            tracking = TrackingType.REPS, rows = listOf(SetRowState(0, "1", false, 0.0, 12, false)))
        rule.setContent { AppTheme { WorkoutSetHost(base.copy(exercises = listOf(card)).withWorkoutSelection()) } }
        rule.onNodeWithText("Log 12 reps").assertIsEnabled()
        rule.onNodeWithText("Weight", substring = true).assertDoesNotExist()
    }

    @Test fun concurrentExecutionDisablesBothTimedRepAndCardioStarts() {
        var state by mutableStateOf(workoutSetTimedRepFixture().copy(executionBusy = true))
        rule.setContent { AppTheme { WorkoutSetHost(state) } }
        rule.onNodeWithText("Start").assertIsNotEnabled()
        rule.runOnIdle { state = workoutSetCardioFixture().copy(executionBusy = true) }
        rule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test fun cancelingTimedRepAfterUntickRestoresFirstUnfinishedSet() {
        var state by mutableStateOf(workoutSetTimedRepRunningFixture(index = 1))
        rule.setContent { AppTheme { WorkoutSetHost(state.withWorkoutSelection(), workoutActions().copy(
            onStopTimedRep = { state = state.copy(timedRep = null, executionBusy = false) },
        )) } }
        rule.onNodeWithText("Set 2 of 2").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Set 1 of 2").assertExists()
        rule.onNodeWithText("0:40").assertExists()
        rule.onNodeWithText("Start").assertIsEnabled()
    }

    @Test fun liftSheetTicksAndUnticksKeepIndependentExerciseTargets() {
        val base = workoutSetSupersetFixture().exercises.single()
        var half by mutableStateOf(Slot.MAIN)
        var row by mutableStateOf(SetRowState(0, "1", false, 50.0, 10, true, PartnerRowState(90.0, 12, done = true)))
        val writes = mutableListOf<Pair<String, Boolean>>()
        rule.setContent { AppTheme {
            LiftSheet(base.copy(rows = listOf(row)), half, null, workoutActions().copy(
                onToggleDone = { _, _, checked, slot ->
                    writes += slot to checked
                    row = if (slot == Slot.MAIN) row.copy(done = checked)
                        else row.copy(partner = requireNotNull(row.partner).copy(done = checked))
                },
            ), false, null, {}, {}, {})
        } }
        rule.onNodeWithTag("lift-check-${Slot.MAIN}-0").assertIsOn().performClick()
        rule.onNodeWithTag("lift-check-${Slot.MAIN}-0").assertIsOff()
        rule.runOnIdle { half = Slot.SS }
        rule.onNodeWithTag("lift-check-${Slot.SS}-0").assertIsOn().performClick()
        rule.onNodeWithTag("lift-check-${Slot.SS}-0").assertIsOff()
        rule.runOnIdle { half = Slot.MAIN }
        rule.onNodeWithTag("lift-check-${Slot.MAIN}-0").performClick()
        rule.onNodeWithTag("lift-check-${Slot.MAIN}-0").assertIsOn()
        rule.runOnIdle { half = Slot.SS }
        rule.onNodeWithTag("lift-check-${Slot.SS}-0").assertIsOff()
        rule.runOnIdle { assertEquals(listOf(Slot.MAIN to false, Slot.SS to false, Slot.MAIN to true), writes) }
    }

    @Test fun resolvedNullSheetReenablesDayBack() {
        var state by mutableStateOf(workoutDayFixture().copy(openSheet = OpenSheet("D", OpenSheet.Kind.EDITOR, 2L, 2)))
        var backDispatcher: OnBackPressedDispatcher? = null
        var exits = 0
        rule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            AppTheme { DayScreen(state, workoutActions(), DayEditUiState(), workoutEditActions(), onBack = { exits++ }) }
        }
        rule.onNodeWithText("Weight, lb").assertExists()
        rule.runOnIdle {
            state = state.copy(openSheet = null, exercises = state.exercises.map { card ->
                if (card.programExerciseId == 2L) card.copy(rows = card.rows.dropLast(1)) else card
            })
        }
        rule.waitForIdle()
        rule.onNodeWithText("Weight, lb").assertDoesNotExist()
        rule.runOnUiThread { requireNotNull(backDispatcher).onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, exits)
    }
}
