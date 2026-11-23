package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SideResult
import cloud.trotter.log.strength.domain.model.Sides
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.EffortScale
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class HoldTimerSetScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun state(hold: HoldState? = null, weight: Double = 0.0, caution: Boolean = false): DayUiState {
        val base = workoutSetSingleFixture()
        val card = base.exercises.single().copy(
            title = "Plank", exerciseId = "plank", tracking = TrackingType.TIMED,
            headerTarget = HeaderTarget.Prescribed("2 × 0:30", eachSide = true),
            rows = List(2) { SetRowState(it, "${it + 1}", false, weight, 0, false, seconds = 30) },
            focusIndex = 0,
            holdPressureCaution = caution,
        )
        return base.copy(exercises = listOf(card), hold = hold, executionBusy = hold != null)
    }

    private fun hold(heldSeconds: Int = 12, running: Boolean = true, sides: List<Int> = emptyList()) = HoldState(
        HoldEvent(dayId = "A", programExerciseId = 1L, exerciseId = "plank", setIndex = 0,
            targetSeconds = 30, sides = 2, startedAtMillis = 1_000L, wallStart = 1_000L,
            elapsedStart = 1_000L, bootCount = 1, running = running, heldSeconds = sides),
        heldSeconds,
    )

    private fun show(state: DayUiState, actions: DayActions = workoutActions()) {
        rule.setContent { AppTheme { WorkoutSetHost(state.withWorkoutSelection(), actions) } }
    }

    private fun assertLoggedHoldSnackbar(seconds: Int) {
        val base = state()
        val card = base.exercises.single()
        val logged = LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = seconds,
            startedAtMillis = 1_000L, completedAtMillis = 1_000L + seconds * 1_000L)
        val movedOn = base.copy(exercises = listOf(card.copy(
            rows = card.rows.map { if (it.index == 0) it.copy(done = true, seconds = seconds) else it },
            focusIndex = 1,
        ))).withWorkoutSelection()
        val state = movedOn.copy(workout = requireNotNull(movedOn.workout).copy(
            undo = UndoOffer(1L, Slot.MAIN, 0, "Plank", logged, TrackingType.TIMED, base.unit),
        ))
        var undos = 0
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(onUndoLastLog = { undos++ })) } }
        rule.onNodeWithText("✓ Set 1 · ${seconds}s").assertIsDisplayed()
        rule.onNodeWithText("Undo").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, undos) }
    }

    @Test fun `a stopped hold shows the logged snackbar with Undo`() {
        assertLoggedHoldSnackbar(18)
    }

    @Test fun `a late auto logged hold shows the target snackbar with Undo`() {
        assertLoggedHoldSnackbar(30)
    }

    @Test fun `an idle hold offers Start and keeps its editable target`() {
        var started: Pair<Long, Int>? = null
        show(state(), workoutActions().copy(onStartHold = { id, index -> started = id to index }))
        rule.onNodeWithText("30 s").assertIsDisplayed()
        rule.onNodeWithContentDescription("Edit set 1").assertIsEnabled()
        rule.onNodeWithText("Each side, left first").assertIsDisplayed() // #644: left first before Start.
        rule.onNodeWithText("Log 30 s").assertDoesNotExist()
        rule.onNodeWithTag("workout-action-filled").assert(hasText("Start")).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1L to 0, started) }
    }

    @Test fun `a running hold counts up toward its target`() {
        var stops = 0
        show(state(hold()), workoutActions().copy(onStopHold = { stops++ }))
        rule.onNodeWithText("0:12").assertIsDisplayed()
        rule.onNodeWithText("Held").assertIsDisplayed()
        rule.onNodeWithText("Target 0:30").assertIsDisplayed()
        // #644: left first.
        rule.onNodeWithText("Left side").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithContentDescription("Edit set 1").assertDoesNotExist()
        var node: SemanticsNode? = rule.onNodeWithTag("workout-hero", useUnmergedTree = true).fetchSemanticsNode()
        while (node != null) {
            assertFalse(node.config.contains(SemanticsProperties.LiveRegion))
            node = node.parent
        }
        rule.onNodeWithTag("workout-action-tonal").assert(hasText("Stop")).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun `between sides the hold offers Start for the second side`() {
        var started: Pair<Long, Int>? = null
        show(state(hold(heldSeconds = 0, running = false, sides = listOf(30))),
            workoutActions().copy(onStartHold = { id, index -> started = id to index }))
        rule.onNodeWithText("Right side").assertIsDisplayed() // #644: left first.
        rule.onNodeWithText("0:00").assertIsDisplayed()
        rule.onNodeWithText("End hold").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("workout-action-filled").assert(hasText("Start")).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1L to 0, started) }
    }

    @Test fun `between sides End hold calls the stop action`() {
        var stops = 0
        show(state(hold(heldSeconds = 0, running = false, sides = listOf(18))),
            workoutActions().copy(onStopHold = { stops++ }))
        rule.onNodeWithText("End hold").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun `a completed hold shows the logged seconds and disables Start`() {
        show(state(hold(heldSeconds = 20, running = false, sides = listOf(30, 20))))
        rule.onNodeWithText("0:20").assertIsDisplayed()
        rule.onNodeWithText("0:00").assertDoesNotExist()
        rule.onNodeWithTag("workout-action-filled").assert(hasText("Start")).assertIsNotEnabled()
        rule.onNodeWithText("End hold").assertDoesNotExist()
    }

    @Test fun `between sides the clock chip waits at zero`() {
        val waiting = state(hold(heldSeconds = 0, running = false, sides = listOf(30)))
        show(waiting.copy(cardio = cardioFixture(), selectedPageKey = "cardio"))
        rule.onNodeWithText("Plank 0:00").assertIsDisplayed()
    }

    @Test fun `a loaded hold shows its weight while it runs`() {
        show(state(hold(), weight = 25.0))
        rule.onNodeWithText("at 25 lb").assertIsDisplayed()
    }

    @Test fun `the logged message names both sides`() {
        val base = state()
        val card = base.exercises.single()
        val logged = LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 20,
            sides = Sides(SideResult(0.0, 0, 30), SideResult(0.0, 0, 20)))
        val movedOn = base.copy(exercises = listOf(card.copy(
            rows = card.rows.map { if (it.index == 0) it.copy(done = true, seconds = 20) else it },
            focusIndex = 1,
        ))).withWorkoutSelection()
        val loggedState = movedOn.copy(workout = requireNotNull(movedOn.workout).copy(
            undo = UndoOffer(1L, Slot.MAIN, 0, "Plank", logged, TrackingType.TIMED, base.unit),
        ))
        rule.setContent { AppTheme { WorkoutSetHost(loggedState, workoutActions()) } }
        rule.onNodeWithText("✓ Set 1 · L 30 s · R 20 s").assertIsDisplayed()
    }

    @Test fun `an each side hold names the left side first before Start`() {
        show(state())
        rule.onNodeWithText("Each side, left first").assertIsDisplayed()
    }

    @Test fun `an idle hold offers Past target beside Start`() {
        val starts = mutableListOf<Pair<Long, Int>>()
        show(state(), workoutActions().copy(onStartHoldPastTarget = { id, index -> starts += id to index }))
        rule.onNodeWithTag("workout-action-filled").assert(hasText("Start")).assertIsEnabled()
        rule.onNodeWithText("Past target").assertIsDisplayed().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf(1L to 0), starts) }
    }

    @Test fun `a wall sit offers no Past target and its running hold says Keep breathing`() {
        val current = mutableStateOf(state(caution = true))
        rule.setContent { AppTheme { WorkoutSetHost(current.value.withWorkoutSelection(), workoutActions()) } }
        rule.onNodeWithText("Past target").assertDoesNotExist()
        rule.runOnIdle { current.value = state(hold(), caution = true) }
        rule.onNodeWithText("Keep breathing").assertIsDisplayed()
        var node: SemanticsNode? = rule.onNodeWithText("Keep breathing", useUnmergedTree = true).fetchSemanticsNode()
        while (node != null) {
            assertFalse(node.config.contains(SemanticsProperties.LiveRegion))
            node = node.parent
        }
    }

    @Test fun `a running plank shows no breathing cue`() {
        show(state(hold()))
        rule.onNodeWithText("Keep breathing").assertDoesNotExist()
    }

    @Test fun `a logged hold offers words in numeric mode and Next needs no rating`() {
        val base = state().copy(effortScale = EffortScale.REPS_LEFT)
        val card = base.exercises.single()
        val logged = base.copy(exercises = listOf(card.copy(
            rows = card.rows.map { if (it.index == 0) it.copy(done = true) else it }, focusIndex = 1,
        ))).withWorkoutSelection()
        val current = logged.copy(workout = requireNotNull(logged.workout).copy(
            review = WorkoutReview(1L, 0, listOf(Slot.MAIN), emptyList()),
        ))
        var picked: Effort? = null
        var next = 0
        rule.setContent { AppTheme { WorkoutSetHost(current, workoutActions().copy(
            onSetEffort = { _, _, _, effort -> picked = effort }, onContinueWorkout = { next++ },
        )) } }
        for (word in listOf("Easy", "Good", "Hard", "Max")) {
            rule.onNodeWithText(word).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Reps left").assertDoesNotExist()
        rule.onNodeWithTag("workout-effort-4").assertDoesNotExist()
        rule.onNodeWithTag("workout-effort-EASY").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(Effort.EASY, picked) }
        rule.onNodeWithTag("workout-action-filled").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, next) }
    }

    @Test fun `an unrated final hold leaves Finish enabled`() {
        val base = state()
        val card = base.exercises.single().copy(allDone = true, rows = base.exercises.single().rows.map { it.copy(done = true) })
        val logged = base.copy(exercises = listOf(card)).withWorkoutSelection()
        val current = logged.copy(workout = requireNotNull(logged.workout).copy(
            review = WorkoutReview(1L, 1, listOf(Slot.MAIN), emptyList()),
        ))
        rule.setContent { AppTheme { WorkoutSetHost(current, workoutActions()) } }
        rule.onNodeWithTag("workout-action-filled").assertIsEnabled()
        rule.onNodeWithText("Mark effort first").assertDoesNotExist()
    }

    @Test fun `a rated hold snackbar uses its effort word in numeric mode`() {
        val base = state().copy(effortScale = EffortScale.REPS_LEFT)
        val card = base.exercises.single()
        val logged = LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 18, rir = 4)
        val moved = base.copy(exercises = listOf(card.copy(
            rows = card.rows.map { if (it.index == 0) it.copy(done = true, seconds = 18, rir = 4) else it },
            focusIndex = 1,
        ))).withWorkoutSelection()
        val current = moved.copy(workout = requireNotNull(moved.workout).copy(
            undo = UndoOffer(1L, Slot.MAIN, 0, "Plank", logged, TrackingType.TIMED, base.unit),
        ))
        rule.setContent { AppTheme { WorkoutSetHost(current, workoutActions()) } }
        rule.onNodeWithText("Easy", substring = true).assertIsDisplayed()
        // #644: the each-side label now says "left first", so name the numeric reading itself.
        rule.onNodeWithText("4 left", substring = true).assertDoesNotExist()
    }

    @Test fun `the logged snackbar speaks the side names and units`() {
        val base = state()
        val logged = LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 40,
            sides = Sides(SideResult(0.0, 0, 40), SideResult(0.0, 0, 52)))
        val moved = base.copy(exercises = base.exercises.map { card -> card.copy(
            rows = card.rows.map { it.copy(done = it.index == 0) }, focusIndex = 1,
        ) }).withWorkoutSelection()
        val current = moved.copy(workout = requireNotNull(moved.workout).copy(
            undo = UndoOffer(1L, Slot.MAIN, 0, "Plank", logged, TrackingType.TIMED, base.unit),
        ))
        rule.setContent { AppTheme { WorkoutSetHost(current, workoutActions()) } }
        rule.onNodeWithText("✓ Set 1 · L 40 s · R 52 s").assertIsDisplayed()
        rule.onNodeWithContentDescription("✓ Set 1 · left 40 seconds, right 52 seconds").assertIsDisplayed()
        rule.onNodeWithText("Undo").assertIsEnabled()
    }

}
