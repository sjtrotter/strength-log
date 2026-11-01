package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
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
class WorkoutSetScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun singleLiftShowsSetAndPlatesAndGuardsDoubleTaps() {
        var logs = 0
        show(single(), pagerActions().copy(onLogFocusedSet = { logs++ }))
        rule.onNodeWithText("Barbell Back Squat").assertIsDisplayed()
        rule.onNodeWithText("Set 3 of 6 (R3)").assertIsDisplayed()
        rule.onNodeWithText("45 + 25 + 2.5 a side").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        val log = rule.onNodeWithText("Log 190 × 5").assertIsEnabled()
        log.performClick()
        log.performClick()
        rule.runOnIdle { assertEquals(1, logs) }
        rule.mainClock.advanceTimeBy(599, ignoreFrameDuration = true)
        log.performClick()
        rule.runOnIdle { assertEquals(1, logs) }
        rule.mainClock.advanceTimeBy(2, ignoreFrameDuration = true)
        log.performClick()
        rule.runOnIdle { assertEquals(2, logs) }
    }

    @Test fun supersetKeepsBothHalvesAndOpensTheirSets() {
        var state by mutableStateOf(superset())
        val opened = mutableListOf<Long>()
        val actions = pagerActions().copy(onOpenAllSets = { opened += it })
        rule.setContent { AppTheme { WorkoutSetScreen(state, actions, DayEditUiState(), pagerEditActions()) } }
        rule.onNodeWithText("Superset, round 2 of 3").assertIsDisplayed()
        rule.onNode(hasText("Now") and hasTestTag("workout-half-${Slot.MAIN}")).assertExists()
        rule.onNode(hasText("Next") and hasTestTag("workout-half-${Slot.SS}")).assertExists()
        rule.onNode(hasText("120 × 10") and hasTestTag("workout-half-${Slot.SS}")).assertExists()
        for (slot in listOf(Slot.MAIN, Slot.SS)) {
            rule.onNodeWithTag("workout-half-$slot").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        }
        rule.runOnIdle {
            assertEquals(listOf(1L, 1L), opened)
            val card = state.exercises.single()
            state = state.copy(
                exercises = listOf(card.copy(rows = card.rows.map { if (it.index == 1) it.copy(done = true) else it })),
                workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1)),
            )
        }
        rule.onNode(hasText("Done") and hasTestTag("workout-half-${Slot.MAIN}")).assertExists()
        rule.onNode(hasText("80 × 10") and hasTestTag("workout-half-${Slot.MAIN}")).assertExists()
        rule.onNode(hasText("Now") and hasTestTag("workout-half-${Slot.SS}")).assertExists()
        rule.onNodeWithText("Then rest").assertIsDisplayed()
    }

    @Test fun mixedSupersetFormatsThePartnerByItsOwnTracking() {
        val state = superset()
        val card = state.exercises.single().copy(
            partnerTitle = "Pull-Up", partnerTracking = TrackingType.REPS,
            rows = state.exercises.single().rows.map { it.copy(partner = PartnerRowState(0.0, 8)) },
        )
        show(state.copy(exercises = listOf(card)))
        rule.onNode(hasText("8 reps") and hasTestTag("workout-half-${Slot.SS}")).assertIsDisplayed()
        rule.onNodeWithText("0 × 8").assertDoesNotExist()
    }

    @Test fun secondHalfUsesItsOwnPlates() {
        val state = superset()
        val card = state.exercises.single().copy(
            partnerTitle = "Barbell Bench Press", plateLine = "10",
            rows = state.exercises.single().rows.map { row -> row.copy(
                done = row.index <= 1, partner = row.partner?.copy(weightDisplay = 190.0),
            ) },
        )
        show(state.copy(exercises = listOf(card), workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1))))
        rule.onNodeWithText("45 + 25 + 2.5 a side").assertIsDisplayed()
        rule.onNodeWithText("10 a side").assertDoesNotExist()
    }

    @Test fun loadedHoldPartnerIncludesItsLoad() {
        val state = superset()
        val card = state.exercises.single().copy(
            partnerTitle = "Weighted Plank", partnerTracking = TrackingType.TIMED,
            rows = state.exercises.single().rows.map { it.copy(partner = PartnerRowState(25.0, 0, seconds = 45)) },
        )
        show(state.copy(exercises = listOf(card)))
        rule.onNode(hasText("45 s at 25 lb") and hasTestTag("workout-half-${Slot.SS}")).assertIsDisplayed()
    }

    @Test fun missingPartnerRemainsVisibleAsNotAvailable() {
        val state = superset()
        val card = state.exercises.single().copy(rows = state.exercises.single().rows.map { it.copy(partner = null) })
        show(state.copy(exercises = listOf(card)))
        rule.onNode(hasText("Seated Cable Row") and hasText("Not available") and hasTestTag("workout-half-${Slot.SS}"))
            .assertIsDisplayed().assertHasClickAction()
    }

    @Test fun authoredDetailReplacesPlates() {
        val state = single()
        val card = state.exercises.single().copy(headerTarget = HeaderTarget.Prescribed("3 × 12", eachSide = true, resistance = "Red band"))
        show(state.copy(exercises = listOf(card)))
        rule.onNodeWithText("Each side, Red band").assertIsDisplayed()
        rule.onNodeWithText("45 + 25 + 2.5 a side").assertDoesNotExist()
    }

    @Test fun liveRestOffersUndoAdjustmentAndSkipAndKeepsLogTonal() {
        var undos = 0
        var adjustment = 0
        var skips = 0
        var logs = 0
        show(single().copy(rest = pagerRest(), workout = single().workout?.copy(undo = undo())), pagerActions().copy(
            onUndoLastLog = { undos++ }, onAdjustRest = { adjustment = it }, onSkipRest = { skips++ },
            onLogFocusedSet = { logs++ },
        ))
        rule.onNodeWithText("Rest").assertIsDisplayed()
        rule.onNodeWithText("Undo 165 × 5").performClick()
        rule.onNodeWithText("+15 s").performClick()
        rule.onNodeWithText("Skip rest").performClick()
        rule.onNodeWithTag("workout-action-tonal").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, undos)
            assertEquals(15, adjustment)
            assertEquals(1, skips)
            assertEquals(1, logs)
        }
    }

    @Test fun restOffBandHoldsOnlyUndo() {
        var undos = 0
        show(single().copy(workout = single().workout?.copy(undo = undo())), pagerActions().copy(onUndoLastLog = { undos++ }))
        rule.onNodeWithText("Undo 165 × 5").assertIsDisplayed().performClick()
        rule.onNodeWithText("Rest").assertDoesNotExist()
        rule.onNodeWithText("+15 s").assertDoesNotExist()
        rule.onNodeWithText("Skip rest").assertDoesNotExist()
        rule.onAllNodes(hasAnyAncestor(hasTestTag("workout-rest")) and hasText("Undo 165 × 5")).fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
        rule.runOnIdle { assertEquals(1, undos) }
    }

    @Test fun undoForAnotherLiftCarriesItsCatalogShortName() {
        val state = single()
        val offer = undo().copy(programExerciseId = 9L, set = LoggedSet(175.0, 8, SetKind.BACKOFF))
        show(state.copy(workout = state.workout?.copy(undo = offer)))
        rule.onNodeWithText("Undo Squat 175 × 8").assertIsDisplayed()
    }

    @Test fun completedViewShowsSummaryAndSelectsNextWithoutLogging() {
        val state = single()
        val completed = state.exercises.single().copy(
            allDone = true, rows = state.exercises.single().rows.map { it.copy(done = true) },
            summaryLine = "130×5, 165×5, 190×5, 210×3, 235×5, 175×8",
        )
        var nexts = 0
        var logs = 0
        show(state.copy(
            exercises = listOf(completed, completed.copy(programExerciseId = 2L, title = "Barbell Bench Press", allDone = false, rows = completed.rows.map { it.copy(done = false) })),
            workout = state.workout?.copy(shown = WorkoutSelection.Shown.Group("exercise:1", true), focus = null, completedView = true),
        ), pagerActions().copy(onNextWorkoutLift = { nexts++ }, onLogFocusedSet = { logs++ }))
        rule.onNodeWithText("Done, 6 of 6").assertIsDisplayed()
        rule.onNodeWithText(completed.summaryLine).assertIsDisplayed()
        rule.onNodeWithText("Next: Barbell Bench Press").performClick()
        rule.runOnIdle { assertEquals(1, nexts); assertEquals(0, logs) }
    }

    @Test fun allLoggedArmsFinishAtThreeSeconds() {
        rule.mainClock.autoAdvance = false
        val state = single()
        val completed = state.exercises.single().copy(allDone = true, rows = state.exercises.single().rows.map { it.copy(done = true) })
        var finishes = 0
        rule.setContent { AppTheme {
            WorkoutSetScreen(
                state.copy(exercises = listOf(completed), workout = WorkoutState(WorkoutSelection.Shown.AllLogged, null, false, null, 0L)),
                pagerActions().copy(onDone = { finishes++ }), DayEditUiState(), pagerEditActions(),
                nowMillis = { rule.mainClock.currentTime },
            )
        } }
        rule.onNodeWithText("All 6 sets logged").assertIsDisplayed()
        rule.onNodeWithText("Finish").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(2_999L - rule.mainClock.currentTime, ignoreFrameDuration = true)
        rule.onNodeWithText("Finish day A").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(1L, ignoreFrameDuration = true)
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Finish day A").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, finishes) }
    }

    @Test fun loadedHoldShowsSecondsAndLoadAndLogsSeconds() {
        val state = single()
        val card = state.exercises.single().copy(
            title = "Weighted Plank", tracking = TrackingType.TIMED, timedShowsWeight = true, plateLine = null,
            rows = listOf(SetRowState(0, "1", false, 25.0, 0, false, seconds = 45)),
        )
        show(state.copy(exercises = listOf(card), workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.MAIN, 0))))
        rule.onNodeWithText("45 s").assertIsDisplayed()
        rule.onNodeWithText("at 25 lb").assertIsDisplayed()
        rule.onNodeWithText("Log 45 s").assertIsEnabled()
    }

    @Test fun supersetLogStaysReachableAtLargeFont() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme { WorkoutSetScreen(superset(), pagerActions(), DayEditUiState(), pagerEditActions()) }
            }
        }
        rule.onNodeWithText("Log 80 × 10").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun liftAndHeroUseExistingSheetCallbacks() {
        var sets: Long? = null
        var editor: Pair<Long, Int>? = null
        show(single(), pagerActions().copy(onOpenAllSets = { sets = it }, onOpenSetEditor = { id, index -> editor = id to index }))
        rule.onNodeWithText("All sets").performClick()
        rule.onNodeWithContentDescription("Edit set 3").performClick()
        rule.runOnIdle { assertEquals(1L, sets); assertEquals(1L to 2, editor) }
    }

    @Test fun partialFinishReusesExistingConfirmation() {
        var finishes = 0
        show(single(), pagerActions().copy(onDone = { finishes++ }))
        rule.onNodeWithText("Finish").performClick()
        rule.onNodeWithText("Finish with 2 of 6 sets?").assertIsDisplayed()
        rule.onNodeWithText("Keep going").performClick()
        rule.runOnIdle { assertEquals(0, finishes) }
    }

    @Test fun routingKeepsUnsupportedStatesOnThePager() {
        val state = single()
        assertTrue(state.usesWorkoutSetScreen)
        assertFalse(state.copy(workout = null).usesWorkoutSetScreen)
        assertFalse(state.copy(loading = true).usesWorkoutSetScreen)
        assertFalse(state.copy(hasProgram = false).usesWorkoutSetScreen)
        assertFalse(state.copy(workout = state.workout?.copy(shown = WorkoutSelection.Shown.Empty)).usesWorkoutSetScreen)
        assertFalse(state.copy(workout = state.workout?.copy(shown = WorkoutSelection.Shown.Group("cardio", false))).usesWorkoutSetScreen)
        assertFalse(state.copy(cardioRunning = true).usesWorkoutSetScreen)
        assertFalse(state.copy(exercises = state.exercises.map { it.copy(timedRepDurationSeconds = 40) }).usesWorkoutSetScreen)
        assertTrue(state.copy(workout = state.workout?.copy(shown = WorkoutSelection.Shown.AllLogged)).usesWorkoutSetScreen)
    }

    private fun show(state: DayUiState, actions: DayActions = pagerActions()) {
        rule.setContent { AppTheme { WorkoutSetScreen(state, actions, DayEditUiState(), pagerEditActions()) } }
    }

    private fun single(): DayUiState {
        val rows = List(6) { index -> SetRowState(index, "R${index + 1}", false, 190.0, 5, index < 2, kind = SetKind.RAMP, rampIndex = index + 1) }
        val card = ExerciseCardState(
            programExerciseId = 1L, position = 0, title = "Barbell Back Squat", exerciseId = "bb_back_squat",
            isMain = true, isSuperset = false, hasWarmupHint = false, headerTarget = HeaderTarget.Goal("235"), perHand = false,
            equipment = listOf(Equipment.BARBELL), allDone = false, rows = rows, plateLine = "45 + 25 + 2.5",
        )
        return DayUiState(hasProgram = true, viewDayId = "A", exercises = listOf(card), workout = WorkoutState(
            WorkoutSelection.Shown.Group("exercise:1", false), WorkoutFocus(1L, Slot.MAIN, 2), false, null, null,
        ))
    }

    private fun superset(): DayUiState {
        val state = single()
        val card = state.exercises.single().copy(
            title = "DB Bench Press", isSuperset = true, partnerTitle = "Seated Cable Row", partnerTracking = TrackingType.WEIGHTED,
            plateLine = null, rows = List(3) { index -> SetRowState(index, "${index + 1}", false, 80.0, 10, index == 0,
                partner = PartnerRowState(120.0, 10, done = index == 0)) },
        )
        return state.copy(exercises = listOf(card), workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.MAIN, 1)))
    }

    private fun undo() = UndoOffer(1L, Slot.MAIN, 1, "Barbell Back Squat", LoggedSet(165.0, 5, SetKind.RAMP), TrackingType.WEIGHTED, WeightUnit.LB, null)
}
