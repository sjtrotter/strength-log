package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.MovementPattern
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

/**
 * Pins issue #122: a card's swap chip reaches the same swap mutation as the
 * day-edit sheet's Edit -> Swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardSwapTest {

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
                isMain = true,
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

    @Test
    fun swapChipOnAnExpandedCardOpensThePickerAndRecordsThePositionAndTargetId() {
        val swaps = mutableListOf<Pair<Int, String>>()
        val target = ExerciseCatalog.CODE_ONLY.substitutionsFor("bb_back_squat").first()
        setDayContent(dayEditActions = swapRecorder(swaps))

        composeTestRule.onNodeWithContentDescription("Swap exercise").performClick()
        composeTestRule.onNodeWithText(target.name).performClick()

        assertEquals(listOf(0 to target.id), swaps)
    }

    /** The whole point of #122: the shortcut is the same mutation, not a
     *  second swap path that could drift from the sheet's. */
    @Test
    fun theCardSwapChipAndTheSheetSwapProduceTheSameMutation() {
        val swaps = mutableListOf<Pair<Int, String>>()
        val target = ExerciseCatalog.CODE_ONLY.substitutionsFor("bb_back_squat").first()
        setDayContent(dayEditActions = swapRecorder(swaps))

        // From the card: chip -> pick.
        composeTestRule.onNodeWithContentDescription("Swap exercise").performClick()
        composeTestRule.onNodeWithText(target.name).performClick()

        // From the sheet: ✎ -> Edit -> Swap exercise -> pick.
        composeTestRule.onNodeWithContentDescription("Edit day").performClick()
        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithText("Swap exercise").performClick()
        composeTestRule.onNodeWithText(target.name).performClick()

        assertEquals(listOf(0 to target.id, 0 to target.id), swaps)
    }

    /**
     * The chip's touch target is Material's 48dp minimum, which is larger than
     * the chip. It must claim that space, not borrow it: a tap on the ADD
     * WEIGHT pill, or anywhere on the collapse toggle, has to do what it looks
     * like it does.
     */
    @Test
    fun theSwapChipsTouchTargetOverlapsNeitherTheWeightPillNorTheCollapseToggle() {
        val state = dayState.copy(
            exercises = listOf(
                dayState.exercises.single().copy(
                    weightSwap = WeightSwapAffordance("weighted_plank", "Weighted Plank", isRemove = false),
                ),
            ),
        )
        setDayContent(dayState = state)

        val chip = composeTestRule.onNodeWithContentDescription("Swap exercise").fetchSemanticsNode().boundsInRoot
        val pill = composeTestRule.onNodeWithText("+ ADD WEIGHT").fetchSemanticsNode().boundsInRoot
        val collapse = composeTestRule.onNode(hasStateDescription("Expanded")).fetchSemanticsNode().boundsInRoot

        assertFalse("the chip's target reaches the ADD WEIGHT pill", chip.overlaps(pill))
        assertFalse("the chip's target sits on the collapse toggle", chip.overlaps(collapse))
    }

    @Test
    fun aSlotWithNoResolvablePatternGetsNoSwapChip() {
        val state = dayEditState.copy(
            slots = listOf(dayEditState.slots.single().copy(pattern = null)),
        )
        setDayContent(dayEditState = state)

        assertTrue(
            composeTestRule.onAllNodesWithContentDescription("Swap exercise")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun collapsedCardsHideTheSwapChip() {
        val state = dayState.copy(
            exercises = listOf(dayState.exercises.single().copy(collapsed = true)),
        )
        setDayContent(dayState = state)

        assertTrue(
            composeTestRule.onAllNodesWithContentDescription("Swap exercise")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun swapChipExposesItsAccessibleNameAndFires() {
        var tapped = false
        composeTestRule.setContent {
            AppTheme {
                SwapExerciseChip(onClick = { tapped = true })
            }
        }

        val chip = composeTestRule.onNodeWithContentDescription("Swap exercise")
        chip.assertExists()
        chip.performClick()
        assertTrue(tapped)
    }

    private fun setDayContent(
        dayState: DayUiState = this.dayState,
        dayEditState: DayEditUiState = this.dayEditState,
        dayEditActions: DayEditActions = swapRecorder(mutableListOf()),
    ) {
        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = dayState,
                    actions = dayActions(),
                    dayEditState = dayEditState,
                    dayEditActions = dayEditActions,
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
    )

    private fun swapRecorder(swaps: MutableList<Pair<Int, String>>) = DayEditActions(
        onSwap = { position, exerciseId -> swaps += position to exerciseId },
        onAdd = {},
        onRemove = {},
        onSetSuperset = { _, _ -> },
        onRemoveSuperset = {},
        onResetToTemplate = {},
    )
}
