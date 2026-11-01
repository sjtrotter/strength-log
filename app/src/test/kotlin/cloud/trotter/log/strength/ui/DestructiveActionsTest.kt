package cloud.trotter.log.strength.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.activity.ComponentDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayActions
import cloud.trotter.log.strength.ui.day.DayEditActions
import cloud.trotter.log.strength.ui.day.DayEditSlotState
import cloud.trotter.log.strength.ui.day.DayEditUiState
import cloud.trotter.log.strength.ui.day.withWorkoutSelection
import cloud.trotter.log.strength.ui.day.OpenSheet
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.ExerciseCardState
import cloud.trotter.log.strength.ui.day.HeaderTarget
import cloud.trotter.log.strength.ui.day.RemovedSet
import cloud.trotter.log.strength.ui.day.SetRowState
import cloud.trotter.log.strength.ui.setup.SetupActions
import cloud.trotter.log.strength.ui.setup.SetupScreen
import cloud.trotter.log.strength.ui.setup.SetupUiState
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The three destructive actions have different gates. What's pinned here is
 * the *gate*, not the dialog's pixels — the rare ones (clear the day's
 * checkmarks, reset the rest timers) must not reach their action until a
 * confirm is taken, and the frequent one (Remove set in the editor) must reach
 * its undo without a confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class DestructiveActionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val discardAction: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.day_menu_clear_checks)

    /** [F] */
    @Test fun discardIsShownWithAnOpenRun() {
        setDayContent(runOpen = true)
        composeTestRule.onNodeWithContentDescription("Day A lifts", substring = true).performClick()
        composeTestRule.onNodeWithText(discardAction).assertIsDisplayed()
    }

    // --- Discard workout (day menu) -----------------------

    @Test
    fun discardingTheWorkoutAsksFirstAndOnlyFiresOnConfirm() {
        var cleared = 0
        setDayContent(onDiscardWorkout = { cleared++ })

        composeTestRule.onNodeWithContentDescription("Day A lifts", substring = true).performClick()
        composeTestRule.onNodeWithText(discardAction).performClick()
        assertEquals("the menu tap must only open the dialog", 0, cleared)

        composeTestRule.onNodeWithText("Discard").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun cancellingTheDiscardDialogLeavesTheDayAlone() {
        var cleared = 0
        setDayContent(onDiscardWorkout = { cleared++ })

        composeTestRule.onNodeWithContentDescription("Day A lifts", substring = true).performClick()
        composeTestRule.onNodeWithText(discardAction).performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, cleared)
        // The dialog is gone, so the menu action is reachable again.
        composeTestRule.onNodeWithContentDescription("Day A lifts", substring = true).performClick()
        composeTestRule.onNodeWithText(discardAction).performClick()
        assertEquals(0, cleared)
    }

    /** [F] */
    @Test fun discardIsHiddenWithoutAnOpenRun() {
        setDayContent(runOpen = false)
        composeTestRule.onNodeWithContentDescription("Day A lifts", substring = true).performClick()
        composeTestRule.onNodeWithText(discardAction).assertDoesNotExist()
    }

    // --- set removal: Snackbar undo --------------------------------

    @Test
    fun theUndoOfferIsOneTapBackToTheRow() {
        var undone = 0
        setDayContent(removedSets = listOf(offer(index = 0)), onUndoRemoveSet = { undone++ })

        composeTestRule.onNodeWithText("Undo").performClick()

        assertEquals(1, undone)
    }

    @Test
    fun snackbarUndoRemainsAvailableAfterLeavingTheRemovedSetsPage() {
        setDayContent(removedSets = listOf(offer(programExerciseId = 99L)))

        composeTestRule.onNodeWithText("Undo").assertExists()
    }

    /**
     * Only the newest removal is on offer: an older entry's index describes a
     * list that no longer exists, so exactly one line shows however deep the
     * stack goes.
     */
    @Test
    fun onlyTheNewestRemovalIsOffered() {
        setDayContent(removedSets = listOf(offer(index = 0), offer(index = 0)))

        composeTestRule.onAllNodesWithText("Undo").assertCountEquals(1)
    }

    /** Finishing a lift by removing its last undone set must not hide Snackbar undo. */
    @Test
    fun aRemovalThatFinishesTheCardKeepsSnackbarUndoUntilTheOfferIsGone() {
        val base = dayState()
        var state by mutableStateOf(base.copy(exercises = base.exercises.map { card ->
            card.copy(rows = listOf(
                card.rows.single().copy(done = true),
                card.rows.single().copy(index = 1, kindLabel = "R2"),
            ))
        }))
        var offers by mutableStateOf(emptyList<RemovedSet>())
        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = state.withWorkoutSelection(),
                    actions = noopActions(onDiscardWorkout = {}, onRemoveSet = {}),
                    dayEditState = dayEditState(),
                    dayEditActions = noopEditActions(),
                    removedSets = offers,
                    onUndoRemoveSet = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Log 5 reps").assertIsDisplayed()
        // Same order the ViewModel writes them in: the offer opens, then removal
        // of the last undone row leaves only logged work on the set screen.
        composeTestRule.runOnIdle { offers = listOf(offer(index = 1)) }
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed()
        composeTestRule.runOnIdle {
            state = state.copy(exercises = state.exercises.map { card ->
                card.copy(allDone = true, rows = card.rows.dropLast(1))
            })
        }
        composeTestRule.onNodeWithText("All 1 sets logged").assertIsDisplayed()
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed()

        composeTestRule.runOnIdle { offers = emptyList() }
        composeTestRule.onNodeWithText("Undo").assertDoesNotExist()
        composeTestRule.onNodeWithText("All 1 sets logged").assertIsDisplayed()
    }

    // --- dismissing a confirm is not taking it --------------------------------
    //
    // Back is the route worth pinning: it must close the confirm, not pop the
    // screen from under it and leave the overlay behind. It is also the same `onDismissRequest` a tap on the scrim runs, so these cover
    // both — the scrim tap itself is a window-level touch Robolectric does not
    // route, so it stays an on-device check rather than a simulated one.

    @Test
    fun systemBackDismissesTheDiscardDialogWithoutChangingAnything() {
        var cleared = 0
        setDayContent(onDiscardWorkout = { cleared++ })

        composeTestRule.onNodeWithContentDescription("Day A lifts", substring = true).performClick()
        composeTestRule.onNodeWithText(discardAction).performClick()
        composeTestRule.onNodeWithText("Discard").assertIsDisplayed()

        composeTestRule.runOnIdle { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }

        assertEquals(0, cleared)
        composeTestRule.onNodeWithText("Discard").assertDoesNotExist()
    }

    @Test
    fun systemBackDismissesTheRestTimerDialogWithoutResettingAnything() {
        var reset = 0
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions(onRestOverridesReset = { reset++ })) }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("RESET DEFAULTS"))
        composeTestRule.onNodeWithText("RESET DEFAULTS").performClick()
        composeTestRule.onNodeWithText("Reset").assertIsDisplayed()

        composeTestRule.runOnIdle { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }

        assertEquals(0, reset)
        composeTestRule.onNodeWithText("Reset").assertDoesNotExist()
        // The screen behind it is still the screen, not a popped route.
        composeTestRule.onNodeWithText("RESET DEFAULTS").assertIsDisplayed()
    }

    // --- rest-timer RESET DEFAULTS (setup) ------------------------------------

    @Test
    fun resettingTheRestTimersAsksFirstAndOnlyFiresOnConfirm() {
        var reset = 0
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions(onRestOverridesReset = { reset++ })) }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("RESET DEFAULTS"))
        composeTestRule.onNodeWithText("RESET DEFAULTS").performClick()
        assertEquals("the row tap must only open the dialog", 0, reset)

        composeTestRule.onNodeWithText("Reset").performClick()
        assertEquals(1, reset)
    }

    @Test
    fun cancellingTheRestTimerResetKeepsEveryOverride() {
        var reset = 0
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions(onRestOverridesReset = { reset++ })) }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("RESET DEFAULTS"))
        composeTestRule.onNodeWithText("RESET DEFAULTS").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, reset)
    }

    // --- fixtures --------------------------------------------------------------

    private fun setDayContent(
        runOpen: Boolean = true,
        removedSets: List<RemovedSet> = emptyList(),
        onDiscardWorkout: () -> Unit = {},
        onRemoveSet: () -> Unit = {},
        onUndoRemoveSet: () -> Unit = {},
    ) {
        var state by mutableStateOf(dayState().copy(runOpen = runOpen).withWorkoutSelection())
        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = state,
                    actions = noopActions(onDiscardWorkout = onDiscardWorkout, onRemoveSet = onRemoveSet).copy(
                        onOpenDaySheet = { state = state.copy(openSheet = OpenSheet("A", OpenSheet.Kind.DAY, 0, 0)) },
                        onCloseAllSets = { state = state.copy(openSheet = null) },
                    ),
                    dayEditState = dayEditState(),
                    dayEditActions = noopEditActions(),
                    removedSets = removedSets,
                    onUndoRemoveSet = onUndoRemoveSet,
                )
            }
        }
    }

    private fun offer(programExerciseId: Long = 1L, index: Int = 0) = RemovedSet(
        programExerciseId = programExerciseId,
        index = index,
        dayId = "A",
        runId = "run-A",
        main = LoggedSet(210.0, 5, SetKind.WORK),
    )

    /**
     * One short card, so the footer is composed without scrolling the list, and
     * a REPS row rather than a weighted one — a weighted row wants two steppers
     * plus a ✓ and a × on 411dp and pushes the × off the card, which would make
     * "the remove button answers to a tap" untestable here for a reason
     * unrelated to the confirmation gate.
     */
    private fun dayState() = DayUiState(
        hasProgram = true,
        runOpen = true,
        viewDayId = "A",
        dayIndex = 0,
        dayTitle = "Lower — squat focus",
        emphasisLine = "",
        unit = WeightUnit.LB,
        nextDayId = "B",
        exercises = listOf(
            ExerciseCardState(
                programExerciseId = 1L,
                position = 0,
                title = "Barbell Back Squat",
                isMain = false,
                isSuperset = false,
                hasWarmupHint = false,
                headerTarget = HeaderTarget.Goal("235"),
                perHand = false,
                tracking = TrackingType.REPS,
                allDone = false,
                rows = listOf(
                    SetRowState(0, "R1", isTop = false, weightDisplay = 130.0, reps = 5, done = false),
                ),
            ),
        ),
    )

    private fun dayEditState() = DayEditUiState(
        catalog = ExerciseCatalog.CODE_ONLY,
        slots = listOf(
            DayEditSlotState(
                programExerciseId = 1L,
                position = 0,
                exerciseId = "bb_back_squat",
                title = "Barbell Back Squat",
                pattern = MovementPattern.SQUAT_BILATERAL,
                isSuperset = false,
            ),
        ),
    )

    private fun noopActions(onDiscardWorkout: () -> Unit, onRemoveSet: () -> Unit) = DayActions(
        onWeightChange = { _, _, _, _ -> },
        onRepsChange = { _, _, _, _ -> },
        onSecondsChange = { _, _, _, _ -> },
        onToggleDone = { _, _, _, _ -> },
        onAddSet = { _, _ -> },
        onRemoveSet = { _, _, _ -> onRemoveSet() },
        onDiscardWorkout = onDiscardWorkout,
        onDone = {},
        onCreateExercise = {},
        onSetUpProgram = {},
    )

    private fun noopEditActions() = DayEditActions(
        onSwap = { _, _ -> },
        onAdd = {},
        onRemove = {},
        onSetSuperset = { _, _ -> },
        onRemoveSuperset = {},
        onResetToTemplate = {},
    )

    private fun setupActions(onRestOverridesReset: () -> Unit) = SetupActions(
        onBodyweightChange = {},
        onAgeChange = {},
        onLevelChange = {},
        onEmphasisChange = {},
        onUnitToggle = {},
        onRestTimerEnabledChange = {},
        onRestOverrideChange = { _, _ -> },
        onRestOverridesReset = onRestOverridesReset,
        onCreateCustomExercise = {},
        onOpenBackup = {},
        onOpenLicenses = {},
        onBack = {},
    )
}
