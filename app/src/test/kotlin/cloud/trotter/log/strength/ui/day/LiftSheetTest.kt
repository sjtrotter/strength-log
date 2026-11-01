package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.units.WeightUnit
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

    @Test fun prescribedHeadersShowEveryTargetInBothUnitsAndReadTheVisibleLabel() {
        val cases = listOf(
            Triple(PrescribedTarget.LoadReps(95.0, 10), "Target 3 × 10 · 95 lb", "Target 3 × 10 · 43.09 kg"),
            Triple(PrescribedTarget.Reps(12), "Target 3 × 12", "Target 3 × 12"),
            Triple(PrescribedTarget.Hold(45), "Target 3 × 0:45", "Target 3 × 0:45"),
            Triple(PrescribedTarget.Hold(45, 25.0), "Target 3 × 0:45 · 25 lb", "Target 3 × 0:45 · 11.34 kg"),
            Triple(PrescribedTarget.RepsInTime(120), "Target 2:00", "Target 2:00"),
            Triple(PrescribedTarget.RepsInTime(120, 40), "Target 2:00 · aim 40", "Target 2:00 · aim 40"),
        )
        val base = workoutLiftSheetFixture().exercises.single().copy(lastTimeDisplay = "3 × 12", lastTimeDate = null)
        var card by mutableStateOf(base)
        rule.setContent { AppTheme {
            LiftSheet(card, Slot.MAIN, null, workoutActions(), false, null, {}, {}, {})
        } }
        for ((target, lb, kg) in cases) {
            for ((unit, label) in listOf(WeightUnit.LB to lb, WeightUnit.KG to kg)) {
                rule.runOnIdle {
                    card = base.copy(headerTarget = DayScreenBuilder.headerTarget(
                        ProgramExercise("weighted_plank", targetSets = 3,
                            prescription = Prescription(target, eachSide = true, resistance = "Red band")),
                        GoalTarget.Weight(235.0, perHand = false), unit,
                    ))
                }
                val support = "$label. Last time 3 × 12. Each side · Red band"
                rule.onNodeWithText(support).assertIsDisplayed()
                rule.onNodeWithContentDescription(support).assertIsDisplayed()
            }
        }
    }

    @Test fun squatShowsHistoryNotationFocusAndIndependentRowActions() {
        val state = workoutLiftSheetFixture()
        val card = state.exercises.single().copy(rows = state.exercises.single().rows.map {
            if (it.index == 1) it.copy(done = false) else it
        })
        var tick: List<Any>? = null
        var edited: Pair<Long, Int>? = null
        var swapped: Pair<Int, String>? = null
        rule.setContent { AppTheme {
            LiftSheet(card, Slot.MAIN, state.workout?.focus, workoutActions().copy(
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
            LiftSheet(state.exercises.single(), Slot.SS, state.workout?.focus, workoutActions().copy(
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
                dayEditActions = workoutEditActions().copy(
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
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(
            onOpenSetEditor = { id, index -> state = state.copy(openSheet = OpenSheet("A", OpenSheet.Kind.EDITOR, id, index)) },
        )) } }
        rule.onNodeWithTag("lift-set-3").performClick()
        rule.onNodeWithText("Ramp set 4").assertIsDisplayed()
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
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(onUndoWorkoutSwap = { undos++ })) } }
        rule.onNodeWithText("Swapped to Hack Squat").assertIsDisplayed()
        rule.onNodeWithText("Swap", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Undo").performClick()
        rule.runOnIdle { assertEquals(1, undos) }
    }

    @Test fun removalExpiryKeepsNewerSwapSnackbarAndUndo() {
        var state by mutableStateOf(workoutLiftSheetFixture())
        var removed by mutableStateOf(listOf(RemovedSet(1L, 2, "A", "run",
            LoggedSet(190.0, 5, SetKind.RAMP))))
        var swapUndos = 0
        var removalUndos = 0
        rule.setContent { AppTheme {
            WorkoutSetHost(state, workoutActions().copy(onUndoWorkoutSwap = { swapUndos++ }),
                removedSets = removed, onUndoRemoveSet = { removalUndos++ })
        } }
        rule.onNodeWithText("Set removed").assertIsDisplayed()
        rule.runOnIdle {
            state = state.copy(swapUndo = WorkoutSwapUndo("A", "run", 1L, Slot.MAIN,
                "bb_back_squat", "hack_squat", "Hack Squat"))
        }
        rule.onNodeWithText("Swapped to Hack Squat").assertIsDisplayed()
        // The ViewModel clears this list when the removal window expires.
        rule.runOnIdle { removed = emptyList() }
        rule.onNodeWithText("Swapped to Hack Squat").assertIsDisplayed()
        rule.onNodeWithText("Undo").performClick()
        rule.runOnIdle {
            assertEquals(1, swapUndos)
            assertEquals(0, removalUndos)
        }
    }

    @Test fun destructivePlanSwapConfirmationNamesExerciseAndLoggedCount() {
        val state = workoutLiftSheetFixture().copy(pendingPlanSwap = PendingPlanSwap(
            "A", 1L, 1L, Slot.MAIN, "hack_squat", "Barbell Back Squat", 2,
        ))
        var confirmed = 0
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(onConfirmPlanSwap = { confirmed++ })) } }
        rule.onNodeWithText("Swap Barbell Back Squat? Its 2 logged sets today are removed.").assertIsDisplayed()
        rule.onNodeWithText("Swap").performClick()
        rule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun missingGoalAndHistoryAreOmitted() {
        val state = workoutLiftSheetFixture()
        rule.setContent { AppTheme {
            LiftSheet(state.exercises.single().copy(headerTarget = HeaderTarget.Goal("0"),
                lastTimeDisplay = null, lastTimeDate = null), Slot.MAIN, null, workoutActions(), false, null, {}, {}, {})
        } }
        rule.onNodeWithText("Goal", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Last time", substring = true).assertDoesNotExist()
    }

    @Test fun skipLiftAndNowSetActionsUseTheSheetHalfAndDismiss() {
        val base = workoutLiftSheetFixture()
        var skipped: Pair<Long, String>? = null
        var setSkipped: Pair<Long, String>? = null
        var dismissals = 0
        rule.setContent { AppTheme {
            LiftSheet(base.exercises.single(), Slot.MAIN, base.workout?.focus, workoutActions().copy(
                onSkipLift = { id, half -> skipped = id to half },
                onSkipNowSet = { id, half -> setSkipped = id to half },
            ), true, null, {}, {}, { dismissals++ }, canSkipLift = true)
        } }
        rule.onNodeWithText("Skip this set").performScrollTo().performClick()
        rule.onNodeWithText("Skip for this workout").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1L to Slot.MAIN, skipped)
            assertEquals(1L to Slot.MAIN, setSkipped)
            assertEquals(2, dismissals)
        }
        rule.onNodeWithText("Skip this round").assertDoesNotExist()
    }

    @Test fun partnerNowSetOffersSkipRound() {
        val base = workoutSetSupersetFixture()
        var skipped: Pair<Long, String>? = null
        rule.setContent { AppTheme {
            LiftSheet(base.exercises.single(), Slot.SS, WorkoutFocus(1L, Slot.SS, 1), workoutActions().copy(
                onSkipNowSet = { id, half -> skipped = id to half },
            ), true, null, {}, {}, {})
        } }
        rule.onNodeWithText("Skip this round").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1L to Slot.SS, skipped) }
        rule.onNodeWithText("Skip this set").assertDoesNotExist()
    }

    @Test fun skippedPartnerCanBeBroughtBackFromTheRemainingLiftsSheet() {
        val base = workoutLiftSheetFixture()
        var restored: Pair<Long, String>? = null
        rule.setContent { AppTheme {
            LiftSheet(base.exercises.single(), Slot.MAIN, base.workout?.focus, workoutActions().copy(
                onRestoreSkipped = { id, half -> restored = id to half },
            ), true, null, {}, {}, {},                 skippedPartner = SkippedLift(1L, 0, "Seated Cable Row", Slot.SS))
        } }
        rule.onNodeWithText("Bring back Seated Cable Row").performClick()
        rule.runOnIdle { assertEquals(1L to Slot.SS, restored) }
    }

    @Test fun aLiftWithoutNowDoesNotOfferSkipSet() {
        val base = workoutLiftSheetFixture()
        rule.setContent { AppTheme {
            LiftSheet(base.exercises.single(), Slot.MAIN, null, workoutActions(),
                true, null, {}, {}, {}, canSkipLift = true)
        } }
        rule.onNodeWithText("Skip this set").assertDoesNotExist()
        rule.onNodeWithText("Skip for this workout").performScrollTo().assertIsDisplayed()
    }

    @Test fun lastPlainLiftHasNoSkipWorkoutRowButKeepsSkipSet() {
        val base = workoutLiftSheetFixture()
        rule.setContent { AppTheme {
            WorkoutSetHost(base.copy(openSheet = OpenSheet("A", OpenSheet.Kind.LIFT, 1L, 0)))
        } }
        rule.onNodeWithText("Skip this set").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Note").performScrollTo()
        rule.onNodeWithText("Skip for this workout").assertDoesNotExist()
    }

    @Test fun lastSupersetStillOffersSkipForEitherHalf() {
        val base = workoutSetSupersetFixture()
        var half by mutableStateOf(Slot.MAIN)
        rule.setContent { AppTheme {
            WorkoutSetHost(base.copy(openSheet = OpenSheet("A", OpenSheet.Kind.LIFT, 1L, 0, slot = half)))
        } }
        rule.onNodeWithText("Skip for this workout").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { half = Slot.SS }
        rule.onNodeWithText("Skip for this workout").performScrollTo().assertIsDisplayed()
    }
}
