package cloud.trotter.log.strength.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import cloud.trotter.log.strength.ui.day.DayTab
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

/**
 * Issue #124's second half: the three destructive actions that used to fire on
 * the first tap now cost something. What's pinned here is the *gate*, not the
 * dialog's pixels — the rare ones (clear the day's checkmarks, reset the rest
 * timers) must not reach their action until a confirm is taken, and the
 * frequent one (× on a set row) must reach its undo in one tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class DestructiveActionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --- "Clear today's checkmarks" (day screen footer) -----------------------

    @Test
    fun clearingTheDaysChecksAsksFirstAndOnlyFiresOnConfirm() {
        var cleared = 0
        setDayContent(onClearChecks = { cleared++ })

        composeTestRule.onNodeWithText("Clear today's checkmarks").performClick()
        assertEquals("the footer tap must only open the dialog", 0, cleared)

        composeTestRule.onNodeWithText("Clear").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun cancellingTheClearDialogLeavesTheDayAlone() {
        var cleared = 0
        setDayContent(onClearChecks = { cleared++ })

        composeTestRule.onNodeWithText("Clear today's checkmarks").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, cleared)
        // The dialog is gone, so the footer action is reachable again.
        composeTestRule.onNodeWithText("Clear today's checkmarks").performClick()
        assertEquals(0, cleared)
    }

    // --- the × on a set row: undo, not confirm --------------------------------

    @Test
    fun removingASetStaysOneTap() {
        var removed = 0
        setDayContent(onRemoveSet = { removed++ })

        composeTestRule.onNodeWithContentDescription("Remove set").performClick()

        assertEquals("the × must not grow a confirm", 1, removed)
    }

    @Test
    fun theUndoOfferIsOneTapBackToTheRow() {
        var undone = 0
        setDayContent(
            removedSet = RemovedSet(
                programExerciseId = 1L,
                index = 0,
                dayId = "A",
                main = LoggedSet(210.0, 5, SetKind.WORK),
            ),
            onUndoRemoveSet = { undone++ },
        )

        composeTestRule.onNodeWithText("UNDO").performClick()

        assertEquals(1, undone)
    }

    @Test
    fun noUndoOfferShowsOnACardThatDidNotLoseARow() {
        setDayContent(
            removedSet = RemovedSet(
                programExerciseId = 99L,
                index = 0,
                dayId = "A",
                main = LoggedSet(210.0, 5, SetKind.WORK),
            ),
        )

        composeTestRule.onNodeWithText("UNDO").assertDoesNotExist()
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
        removedSet: RemovedSet? = null,
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
                    removedSet = removedSet,
                    onUndoRemoveSet = onUndoRemoveSet,
                )
            }
        }
    }

    /**
     * One short card, so the footer is composed without scrolling the list, and
     * a REPS row rather than a weighted one — a weighted row wants two steppers
     * plus a ✓ and a × on 411dp and pushes the × off the card (#136), which
     * would make "the remove button answers to a tap" untestable here for a
     * reason that has nothing to do with this issue.
     */
    private fun dayState() = DayUiState(
        hasProgram = true,
        tabs = listOf(DayTab("A", 0, isSuggested = false, isSelected = true)),
        viewDayId = "A",
        dayIndex = 0,
        dayTitle = "Lower — squat focus",
        emphasisLine = "",
        unit = WeightUnit.LB,
        suggestedDayId = "A",
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
                collapsed = false,
                collapsedSummary = "5 sets · GOAL 235",
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
        onToggleCollapse = {},
        onKeepScreenOnChange = {},
        onClearChecks = onClearChecks,
        onDone = {},
        onCreateExercise = {},
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
        onRerunWizard = {},
        onCreateCustomExercise = {},
        onOpenBackup = {},
        onOpenLicenses = {},
        onBack = {},
    )
}
