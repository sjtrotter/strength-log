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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.robolectric.shadows.ShadowDialog

/**
 * Issue #124's second half: the three destructive actions that used to fire on
 * the first tap now cost something. What's pinned here is the *gate*, not the
 * dialog's pixels — the rare ones (clear the day's checkmarks, reset the rest
 * timers) must not reach their action until a confirm is taken, and the
 * frequent one (swipe / TalkBack remove on a set row) must reach its undo
 * without a confirmation.
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
    fun swipingASetRevealsTheGlyphAndRemovesIt() {
        var removed = 0
        setDayContent(onRemoveSet = { removed++ })

        composeTestRule.onNodeWithTag("setRowSwipe").performTouchInput { swipeLeft() }

        composeTestRule.onNodeWithTag("removeSetReveal", useUnmergedTree = true).assertExists()
        assertEquals("the swipe must remove without a confirm", 1, removed)
    }

    @Test
    fun talkBackCanRemoveASetWithoutSwiping() {
        var removed = 0
        setDayContent(onRemoveSet = { removed++ })

        val actions = composeTestRule.onNodeWithTag("setRowSwipe").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        composeTestRule.runOnIdle { actions.single { it.label == "Remove set" }.action() }

        assertEquals(1, removed)
    }

    @Test
    fun theUndoOfferIsOneTapBackToTheRow() {
        var undone = 0
        setDayContent(removedSets = listOf(offer(index = 0)), onUndoRemoveSet = { undone++ })

        composeTestRule.onNodeWithText("UNDO").performClick()

        assertEquals(1, undone)
    }

    @Test
    fun noUndoOfferShowsOnACardThatDidNotLoseARow() {
        setDayContent(removedSets = listOf(offer(programExerciseId = 99L)))

        composeTestRule.onNodeWithText("UNDO").assertDoesNotExist()
    }

    /**
     * Only the newest removal is on offer: an older entry's index describes a
     * list that no longer exists, so exactly one line shows however deep the
     * stack goes.
     */
    @Test
    fun onlyTheNewestRemovalIsOffered() {
        setDayContent(removedSets = listOf(offer(index = 0), offer(index = 0)))

        composeTestRule.onAllNodesWithText("UNDO").assertCountEquals(1)
    }

    /**
     * The case that would have made the five-second promise a lie: removing the
     * last unfinished row finishes the card, which asks it to fold — taking the
     * offer with it, because a collapsed card renders a summary and no rows.
     * The fold has to wait for the window.
     */
    @Test
    fun aRemovalThatFinishesTheCardKeepsItOpenUntilTheOfferIsGone() {
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
        // shortened track lands and the card reads as finished.
        offers = listOf(offer(index = 1))
        state = state.copy(
            exercises = listOf(state.exercises.single().copy(allDone = true, collapsed = true)),
        )
        composeTestRule.onNodeWithText("UNDO").assertIsDisplayed()

        offers = emptyList()
        composeTestRule.onNodeWithText("UNDO").assertDoesNotExist()
        composeTestRule.onNodeWithText("5 sets · GOAL 235").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("Clear today's checkmarks").performClick()
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
        onRerunWizard = {},
        onCreateCustomExercise = {},
        onOpenBackup = {},
        onOpenLicenses = {},
        onBack = {},
    )
}
