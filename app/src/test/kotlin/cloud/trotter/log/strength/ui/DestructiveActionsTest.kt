package cloud.trotter.log.strength.ui

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
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.ExerciseCardState
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
 * Issue #124's second half: the three destructive actions that used to fire on
 * the first tap now cost something. What's pinned here is the *gate*, not the
 * dialog's pixels — the rare ones (clear the day's checkmarks, reset the rest
 * timers) must not reach their action until a confirm is taken, and the
 * frequent one (Remove set in the editor) must reach its undo
 * without a confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class DestructiveActionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --- Clear checks (day menu) -----------------------

    @Test
    fun clearingTheDaysChecksAsksFirstAndOnlyFiresOnConfirm() {
        var cleared = 0
        setDayContent(onClearChecks = { cleared++ })

        composeTestRule.onNodeWithContentDescription("Day menu").performClick()
        composeTestRule.onNodeWithText("Clear checks").performClick()
        assertEquals("the menu tap must only open the dialog", 0, cleared)

        composeTestRule.onNodeWithText("Clear").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun cancellingTheClearDialogLeavesTheDayAlone() {
        var cleared = 0
        setDayContent(onClearChecks = { cleared++ })

        composeTestRule.onNodeWithContentDescription("Day menu").performClick()
        composeTestRule.onNodeWithText("Clear checks").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, cleared)
        // The dialog is gone, so the menu action is reachable again.
        composeTestRule.onNodeWithContentDescription("Day menu").performClick()
        composeTestRule.onNodeWithText("Clear checks").performClick()
        assertEquals(0, cleared)
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
        var state by mutableStateOf(dayState())
        var offers by mutableStateOf(emptyList<RemovedSet>())
        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = state,
                    actions = noopActions(onClearChecks = {}, onRemoveSet = {}),
                    dayEditState = dayEditState(),
                    dayEditActions = noopEditActions(),
                    removedSets = offers,
                    onUndoRemoveSet = {},
                )
            }
        }

        // Same order the ViewModel writes them in: the offer opens, then the
        // shortened track lands and the page reads as finished.
        offers = listOf(offer(index = 1))
        state = state.copy(
            exercises = listOf(state.exercises.single().copy(allDone = true)),
        )
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed()

        offers = emptyList()
        composeTestRule.onNodeWithText("Undo").assertDoesNotExist()
        composeTestRule.onNodeWithText("All sets").assertIsDisplayed()
    }

    // --- dismissing a confirm is not taking it --------------------------------
    //
    // Back is the route worth pinning and the one this issue was about: before
    // the conversion it popped the whole screen, and the overlay stayed. It is
    // also the same `onDismissRequest` a tap on the scrim runs, so these cover
    // both — the scrim tap itself is a window-level touch Robolectric does not
    // route, so it stays an on-device check rather than a simulated one.

    @Test
    fun systemBackDismissesTheClearDialogWithoutClearingAnything() {
        var cleared = 0
        setDayContent(onClearChecks = { cleared++ })

        composeTestRule.onNodeWithContentDescription("Day menu").performClick()
        composeTestRule.onNodeWithText("Clear checks").performClick()
        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()

        composeTestRule.runOnIdle { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }

        assertEquals(0, cleared)
        composeTestRule.onNodeWithText("Clear").assertDoesNotExist()
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
        removedSets: List<RemovedSet> = emptyList(),
        onClearChecks: () -> Unit = {},
        onRemoveSet: () -> Unit = {},
        onUndoRemoveSet: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = dayState(),
                    actions = noopActions(onClearChecks = onClearChecks, onRemoveSet = onRemoveSet),
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
        main = LoggedSet(210.0, 5, SetKind.WORK),
    )

    /**
     * One short card, so the footer is composed without scrolling the list, and
     * a REPS row rather than a weighted one — a weighted row wants two steppers
     * plus a ✓ and a × on 411dp and pushes the × off the card (#136), which
     * would make "the remove button answers to a tap" untestable here for a
     * reason that has nothing to do with this issue.
     */
    private fun dayState() = DayUiState(
        hasProgram = true,

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
                goalDisplay = "235",
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

    private fun noopActions(onClearChecks: () -> Unit, onRemoveSet: () -> Unit) = DayActions(
        onSelectDay = {},
        onWeightChange = { _, _, _, _ -> },
        onRepsChange = { _, _, _, _ -> },
        onSecondsChange = { _, _, _, _ -> },
        onToggleDone = { _, _, _, _ -> },
        onAddSet = { _, _ -> },
        onRemoveSet = { _, _, _ -> onRemoveSet() },
        onSelectPage = {},
        onKeepScreenOnChange = {},
        onClearChecks = onClearChecks,
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
        onCardioModeChange = {},
        onCardioPlacementChange = {},
        onFiveKChange = {},
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
