package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #125, the two halves the ViewModel can't answer for.
 *
 * First: keep-screen-on now lives in the bottom bar beside DONE, and the switch
 * is a pure reflection of the persisted preference — it renders what it is given
 * and reports taps outward, with no local copy to drift.
 *
 * Second: the swap confirmation survives a configuration change. Rotating the
 * phone with "Switch to …?" on screen used to answer the question by throwing it
 * away, because the pending swap lived in a plain `remember`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class KeepScreenOnAndRotationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val dayState = DayUiState(
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
                allDone = false,
                collapsed = false,
                collapsedSummary = "5 sets · GOAL 235",
                rows = listOf(
                    SetRowState(0, "R1", isTop = false, weightDisplay = 130.0, reps = 5, done = false),
                ),
                weightSwap = WeightSwapAffordance("weighted_plank", "Weighted Plank", isRemove = false),
            ),
        ),
    )

    private val dayEditState = DayEditUiState(
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

    // --- the switch, in its new home -----------------------------------------

    @Test
    fun theKeepScreenOnSwitchSitsInTheBottomBarBesideDone() {
        setDayContent()

        val switch = composeTestRule.onNodeWithText("KEEP ON").fetchSemanticsNode().boundsInRoot
        val done = composeTestRule.onNodeWithText("DONE — ADVANCE TO DAY B").fetchSemanticsNode().boundsInRoot

        assertTrue("the switch is not on DONE's line", switch.overlaps(done.copy(left = 0f, right = 10_000f)))
        assertTrue("the switch is not to the right of DONE", switch.left >= done.right)
    }

    /** It moved out of the header, it did not get cloned into it. */
    @Test
    fun theHeaderNoLongerCarriesAKeepScreenOnControl() {
        setDayContent()

        assertEquals(1, composeTestRule.onAllNodesWithText("KEEP ON").fetchSemanticsNodes().size)
    }

    @Test
    fun theSwitchRendersThePreferenceAndReportsTapsOutward() {
        val taps = mutableListOf<Boolean>()
        setDayContent(dayState = dayState.copy(keepScreenOn = true), onKeepScreenOnChange = { taps += it })

        composeTestRule.onNodeWithText("KEEP ON").assertIsOn().performClick()

        // The switch owns no copy of the state: it asked for OFF and stayed ON,
        // because only the persisted preference can turn it off.
        assertEquals(listOf(false), taps)
        composeTestRule.onNodeWithText("KEEP ON").assertIsOn()
    }

    @Test
    fun theSwitchReadsOffWhenThePreferenceIsOff() {
        setDayContent()

        composeTestRule.onNodeWithText("KEEP ON").assertIsOff()
    }

    // --- rotation ------------------------------------------------------------

    @Test
    fun theSwapConfirmationSurvivesAConfigurationChange() {
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            AppTheme {
                DayScreen(
                    state = dayState,
                    actions = dayActions(),
                    dayEditState = dayEditState,
                    dayEditActions = noopEditActions(),
                )
            }
        }

        composeTestRule.onNodeWithText("+ ADD WEIGHT").performClick()
        composeTestRule.onNodeWithText("Switch to Weighted Plank?").assertExists()

        restoration.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("Switch to Weighted Plank?").assertExists()
    }

    /** And it is still the *same* question — confirming after the rotation
     *  performs the swap it was opened for, once. */
    @Test
    fun confirmingTheRestoredDialogStillPerformsTheSwap() {
        val swaps = mutableListOf<Pair<Int, String>>()
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            AppTheme {
                DayScreen(
                    state = dayState,
                    actions = dayActions(),
                    dayEditState = dayEditState,
                    dayEditActions = noopEditActions(swaps),
                )
            }
        }

        composeTestRule.onNodeWithText("+ ADD WEIGHT").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("Switch").performClick()

        assertEquals(listOf(0 to "weighted_plank"), swaps)
    }

    private fun setDayContent(
        dayState: DayUiState = this.dayState,
        onKeepScreenOnChange: (Boolean) -> Unit = {},
    ) {
        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = dayState,
                    actions = dayActions(onKeepScreenOnChange),
                    dayEditState = dayEditState,
                    dayEditActions = noopEditActions(),
                )
            }
        }
    }

    private fun dayActions(onKeepScreenOnChange: (Boolean) -> Unit = {}) = DayActions(
        onSelectDay = {},
        onWeightChange = { _, _, _, _ -> },
        onRepsChange = { _, _, _, _ -> },
        onSecondsChange = { _, _, _, _ -> },
        onToggleDone = { _, _, _, _ -> },
        onAddSet = { _, _ -> },
        onRemoveSet = { _, _, _ -> },
        onToggleCollapse = {},
        onKeepScreenOnChange = onKeepScreenOnChange,
        onClearChecks = {},
        onDone = {},
        onCreateExercise = {},
    )

    private fun noopEditActions(swaps: MutableList<Pair<Int, String>> = mutableListOf()) = DayEditActions(
        onSwap = { position, exerciseId -> swaps += position to exerciseId },
        onAdd = {},
        onRemove = {},
        onSetSuperset = { _, _ -> },
        onRemoveSuperset = {},
        onResetToTemplate = {},
    )
}
