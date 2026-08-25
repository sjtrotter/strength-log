package cloud.trotter.log.strength.ui.day

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #126 on screen: a workout that shows it has started, and one that says
 * what it was when it ends.
 *
 * The ordering test is the one worth having. Both post-DONE surfaces are raised
 * by the same call, so the only thing that decides what the lifter experiences
 * first is the order [DayScreen] declares them in — a detail a well-meaning
 * tidy-up could reverse without noticing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SessionLifecycleScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --- progress folded into the day overline -------------------------------

    @Test
    fun aDayNothingIsTickedOnSaysNothingAboutProgress() {
        setDayContent(dayState(done = 0))

        assertEquals(0, composeTestRule.onAllNodesWithText("OF 3", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theOverlineAddsTheCountOnceTheFirstSetIsTicked() {
        setDayContent(dayState(done = 1))

        // MediumTopAppBar composes its title twice (collapsed and expanded rows).
        composeTestRule.onAllNodesWithText("DAY A · 1 OF 3", useUnmergedTree = true).onFirst().assertExists()
    }

    @Test
    fun theOverlineReachesTheTotalWhenEveryRoundIsTicked() {
        setDayContent(dayState(done = 3))

        // MediumTopAppBar composes its title twice (collapsed and expanded rows).
        composeTestRule.onAllNodesWithText("DAY A · 3 OF 3", useUnmergedTree = true).onFirst().assertExists()
    }

    // --- the receipt ---------------------------------------------------------

    @Test
    fun theReceiptReadsBackTheSessionThatJustEnded() {
        setDayContent(dayState(done = 3), receipt = receipt)

        composeTestRule.onNodeWithText("DAY A COMPLETE").assertExists()
        composeTestRule.onNodeWithText("18").assertExists()
        composeTestRule.onNodeWithText("235×3").assertExists()
        composeTestRule.onNodeWithText("DAY B · UPPER").assertExists()
    }

    @Test
    fun itsTwoActionsReportOutward() {
        var shares = 0
        var finishes = 0
        setDayContent(dayState(done = 3), receipt = receipt, onShare = { shares++ }, onFinish = { finishes++ })

        composeTestRule.onNodeWithText("SHARE").assertHasClickAction().performClick()
        composeTestRule.onNodeWithText("BACK TO TODAY").assertHasClickAction().performClick()

        assertEquals(1, shares)
        assertEquals(1, finishes)
    }

    // --- and the order the two of them arrive in ------------------------------

    /**
     * The cascade is read first, and the receipt is not merely painted under it
     * — it is not composed at all. Drawing order alone would leave BACK TO TODAY
     * in the semantics tree while the celebration is up, where TalkBack could
     * fire it and pop the route out from under the scrim.
     */
    @Test
    fun theReceiptIsNotEvenPresentWhileTheCascadeIsUp() {
        setBothPending()

        composeTestRule.onNodeWithText("NEW RAMP").assertExists()
        composeTestRule.onNodeWithText("BACK TO TODAY").assertDoesNotExist()
        composeTestRule.onNodeWithText("SHARE").assertDoesNotExist()
        composeTestRule.onNodeWithText("DAY A COMPLETE").assertDoesNotExist()
    }

    /** Dismiss the cascade and the ledger is what's underneath — the news the
     *  session made, then the account of it, then the way out. */
    @Test
    fun dismissingTheCascadeRevealsTheReceipt() {
        val finishes = setBothPending()

        composeTestRule.onNodeWithText("NEW RAMP").performClick()

        composeTestRule.onNodeWithText("DAY A COMPLETE").assertExists()
        assertEquals("dismissing the cascade must not leave the screen", 0, finishes.size)
    }

    /** The whole sequence on system back alone: one press ends the celebration,
     *  the next ends the receipt and leaves for Today. Neither press skips a
     *  surface, and the first never navigates. */
    @Test
    fun systemBackWalksOutOneSurfaceAtATime() {
        val finishes = setBothPending()

        pressBack()
        assertEquals(0, finishes.size)
        composeTestRule.onNodeWithText("NEW RAMP").assertDoesNotExist()
        composeTestRule.onNodeWithText("DAY A COMPLETE").assertExists()

        pressBack()
        assertEquals(1, finishes.size)
    }

    // --- fixtures ------------------------------------------------------------

    private var backDispatcher: OnBackPressedDispatcher? = null

    /** Both post-DONE surfaces pending at once, with the cascade's dismiss wired
     *  the way [DayViewModel] wires it. Returns the record of finish calls. */
    private fun setBothPending(): List<Unit> {
        val finishes = mutableListOf<Unit>()
        composeTestRule.setContent {
            AppTheme {
                backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                var cascade by remember { mutableStateOf<CascadeCeremony?>(ceremony) }
                DayScreen(
                    state = dayState(done = 3),
                    actions = dayActions(),
                    dayEditState = dayEditState,
                    dayEditActions = noopEditActions(),
                    cascadeCeremony = cascade,
                    onDismissCascade = { cascade = null },
                    sessionReceipt = receipt,
                    onFinishSession = { finishes += Unit },
                )
            }
        }
        return finishes
    }

    private fun pressBack() {
        composeTestRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()
    }

    private val ceremony = CascadeCeremony(
        dayIndex = 0,
        lifts = listOf(CascadeLift("Barbell Back Squat", "235", "245")),
    )

    private val receipt = SessionReceipt(
        sessionId = 1L,
        dayIndex = 0,
        headline = "DAY A COMPLETE",
        dayTitle = "Lower",
        completedSetCount = 18,
        totalSetCount = 18,
        setCount = 18,
        strongest = ReceiptLift("Barbell Back Squat", "235×3"),
        nextDayLine = "DAY B · UPPER",
    )

    private fun dayState(done: Int) = DayUiState(
        hasProgram = true,
        tabs = listOf(DayTab("A", 0, isSuggested = true, isSelected = true)),
        viewDayId = "A",
        dayIndex = 0,
        dayTitle = "Lower",
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
                allDone = done >= 3,
                collapsed = false,
                collapsedSummary = "3 sets · GOAL 235",
                rows = List(3) { index ->
                    SetRowState(
                        index = index,
                        kindLabel = "${index + 1}",
                        isTop = false,
                        weightDisplay = 135.0,
                        reps = 5,
                        done = index < done,
                    )
                },
            ),
        ),
    )

    private val dayEditState = DayEditUiState(catalog = ExerciseCatalog.CODE_ONLY)

    private fun setDayContent(
        state: DayUiState,
        receipt: SessionReceipt? = null,
        onShare: () -> Unit = {},
        onFinish: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = state,
                    actions = dayActions(),
                    dayEditState = dayEditState,
                    dayEditActions = noopEditActions(),
                    sessionReceipt = receipt,
                    onShareSession = onShare,
                    onFinishSession = onFinish,
                )
            }
        }
    }

    private fun dayActions() = DayActions(
        onSelectDay = {},
        onWeightChange = { _, _, _, _ -> },
        onRepsChange = { _, _, _, _ -> },
        onSecondsChange = { _, _, _, _ -> },
        onToggleDone = { _, _, _, _ -> },
        onAddSet = { _, _ -> },
        onRemoveSet = { _, _, _ -> },
        onToggleCollapse = {},
        onKeepScreenOnChange = {},
        onClearChecks = {},
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
}
