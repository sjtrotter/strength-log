package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class LiftSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun squatShowsHistoryNotationFocusAndIndependentRowActions() {
        val state = workoutLiftSheetFixture()
        val card = state.exercises.single().copy(rows = state.exercises.single().rows.map {
            if (it.index == 1) it.copy(done = false) else it
        })
        var tick: List<Any>? = null
        var edited: Pair<Long, Int>? = null
        var swapped: Pair<Int, String>? = null
        rule.setContent { AppTheme {
            LiftSheet(card, Slot.MAIN, state.workout?.focus, pagerActions().copy(
                onToggleDone = { id, index, done, slot -> tick = listOf(id, index, done, slot) },
                onOpenSetEditor = { id, index -> edited = id to index },
                onOpenSwapSheet = { position, slot -> swapped = position to slot },
            ), true, null, {}, {}, {})
        } }
        rule.onNodeWithText("Barbell Back Squat").assertIsDisplayed()
        rule.onNodeWithText("Goal 235. Last time 235 × 5, Oct 21.").assertIsDisplayed()
        listOf("R1", "R2", "R3", "R4", "TOP", "B/O").forEach { label ->
            rule.onNodeWithText(label, substring = true).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Now").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("lift-check-${Slot.MAIN}-1").performScrollTo().performClick()
        rule.onNodeWithTag("lift-set-3").performScrollTo().performClick()
        rule.onNodeWithText("Swap exercise").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(1L, 1, true, Slot.MAIN), tick)
            assertEquals(1L to 3, edited)
            assertEquals(0 to Slot.MAIN, swapped)
        }
    }

    @Test fun partnerHasOwnRowsCheckboxAndSwapHalf() {
        val state = workoutSetSupersetFixture()
        var tick: List<Any>? = null
        var swapped: Pair<Int, String>? = null
        rule.setContent { AppTheme {
            LiftSheet(state.exercises.single(), Slot.SS, state.workout?.focus, pagerActions().copy(
                onToggleDone = { id, index, done, slot -> tick = listOf(id, index, done, slot) },
                onOpenSwapSheet = { position, slot -> swapped = position to slot },
            ), true, null, {}, {}, {})
        } }
        rule.onNodeWithText("Seated Cable Row").assertIsDisplayed()
        rule.onNodeWithText("Superset with DB Bench Press.").assertIsDisplayed()
        rule.onNodeWithText("80 × 10", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("lift-check-${Slot.SS}-1").performClick()
        rule.onNodeWithText("Swap exercise").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(1L, 1, true, Slot.SS), tick)
            assertEquals(0 to Slot.SS, swapped)
        }
    }

    @Test fun partnerSwapPickerUsesPerformedPatternAndWorkoutChoice() {
        val catalog = ExerciseCatalog.CODE_ONLY
        val planned = DayEditSlotState(1L, 4, "db_bench", "DB Bench Press", catalog.find("db_bench")!!.pattern,
            true, "cable_row", "Seated Cable Row")
        val editor = DayEditUiState(slots = listOf(planned), catalog = catalog, runOpen = true,
            performedPartnerIds = mapOf(1L to "bb_row"))
        val performed = editor.performedSwapSlot(planned, Slot.SS)
        val candidates = editor.swapCandidates(performed, Slot.SS)
        assertEquals(catalog.find("bb_row")!!.pattern, performed.pattern)
        assertEquals("cable_row", candidates.first().id)
        assertTrue(candidates.all { it.pattern == performed.pattern })
        var picked: Pair<Int, String>? = null
        rule.setContent { AppTheme {
            SlotSwapSheet(performed, requireNotNull(performed.pattern), editor,
                androidx.compose.ui.graphics.Color.White, onSwap = { _, _ -> error("Plan swap") },
                onDismiss = {}, onCreateExercise = {}, half = Slot.SS,
                onWorkoutSwap = { position, id -> picked = position to id })
        } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Seated Cable Row"))
        rule.onNodeWithText("Seated Cable Row").performClick()
        rule.onNodeWithText("This workout").assertIsDisplayed()
        rule.onNodeWithText("Swap").performClick()
        rule.runOnIdle { assertEquals(4 to "cable_row", picked) }
    }

    @Test fun partnerPlanChoiceCallsSetSupersetForThisPosition() {
        val catalog = ExerciseCatalog.CODE_ONLY
        val base = workoutSetSupersetFixture()
        val planned = DayEditSlotState(1L, 0, "db_bench", "DB Bench Press", catalog.get("db_bench").pattern,
            true, "cable_row", "Seated Cable Row")
        var changed: Pair<Int, String>? = null
        rule.setContent { AppTheme {
            WorkoutSetHost(base.copy(openSheet = OpenSheet("A", OpenSheet.Kind.SWAP, 1L, 0, slot = Slot.SS)),
                dayEditState = DayEditUiState(slots = listOf(planned), catalog = catalog, runOpen = true),
                dayEditActions = pagerEditActions().copy(
                    onSetSuperset = { position, id -> changed = position to id },
                    onSwap = { _, _ -> error("MAIN plan swap") },
                    onWorkoutSwapHalf = { _, _, _ -> error("Workout swap") },
                ))
        } }
        rule.onNodeWithText("Swap — Seated Cable Row").assertIsDisplayed()
        rule.onNodeWithText("Barbell Row").performClick()
        rule.onNodeWithText("The plan").performClick()
        rule.onNodeWithText("Swap").performClick()
        rule.runOnIdle { assertEquals(0 to "bb_row", changed) }
    }

    @Test fun sheetToEditorUsesExistingEditorAndOpeningKeepsFocus() {
        val initial = workoutLiftSheetFixture()
        var state by mutableStateOf(initial.copy(openSheet = OpenSheet("A", OpenSheet.Kind.LIFT, 1L, 0)))
        rule.setContent { AppTheme { WorkoutSetHost(state, pagerActions().copy(
            onOpenSetEditor = { id, index -> state = state.copy(openSheet = OpenSheet("A", OpenSheet.Kind.EDITOR, id, index)) },
        )) } }
        rule.onNodeWithTag("lift-set-3").performClick()
        rule.onNodeWithText("R4").assertExists()
        rule.runOnIdle {
            assertEquals(3, state.openSheet?.setIndex)
            assertEquals(initial.workout, state.workout)
        }
    }

    @Test fun workoutOnlySwapSnackbarNamesReplacementAndOffersUndoWithoutConfirm() {
        val base = workoutLiftSheetFixture()
        val state = base.copy(swapUndo = WorkoutSwapUndo("A", "run", 1L, Slot.MAIN,
            "bb_back_squat", "hack_squat", "Hack Squat"))
        var undos = 0
        rule.setContent { AppTheme { WorkoutSetHost(state, pagerActions().copy(onUndoWorkoutSwap = { undos++ })) } }
        rule.onNodeWithText("Swapped to Hack Squat").assertIsDisplayed()
        rule.onNodeWithText("Swap", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Undo").performClick()
        rule.runOnIdle { assertEquals(1, undos) }
    }

    @Test fun destructivePlanSwapConfirmationNamesExerciseAndLoggedCount() {
        val state = workoutLiftSheetFixture().copy(pendingPlanSwap = PendingPlanSwap(
            "A", 1L, 1L, Slot.MAIN, "hack_squat", "Barbell Back Squat", 2,
        ))
        var confirmed = 0
        rule.setContent { AppTheme { WorkoutSetHost(state, pagerActions().copy(onConfirmPlanSwap = { confirmed++ })) } }
        rule.onNodeWithText("Swap Barbell Back Squat? Its 2 logged sets today are removed.").assertIsDisplayed()
        rule.onNodeWithText("Swap").performClick()
        rule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun missingGoalAndHistoryAreOmitted() {
        val state = workoutLiftSheetFixture()
        rule.setContent { AppTheme {
            LiftSheet(state.exercises.single().copy(headerTarget = HeaderTarget.Goal("0"),
                lastTimeDisplay = null, lastTimeDate = null), Slot.MAIN, null, pagerActions(), false, null, {}, {}, {})
        } }
        rule.onNodeWithText("Goal", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Last time", substring = true).assertDoesNotExist()
    }
}
