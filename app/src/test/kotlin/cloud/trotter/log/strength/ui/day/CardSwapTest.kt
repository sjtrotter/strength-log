package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * A page's swap menu reaches the same swap mutation as the day-edit sheet's
 * Edit -> Swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardSwapTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val dayState = DayUiState(
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
                isMain = true,
                isSuperset = false,
                hasWarmupHint = false,
                headerTarget = HeaderTarget.Goal("235"),
                perHand = false,
                allDone = false,

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
    fun pageMenuOpensThePickerAndRecordsThePositionAndTargetId() {
        val swaps = mutableListOf<Pair<Int, String>>()
        val target = ExerciseCatalog.CODE_ONLY.substitutionsFor("bb_back_squat").first()
        setDayContent(dayEditActions = swapRecorder(swaps))

        composeTestRule.onNodeWithContentDescription("Barbell Back Squat menu").performClick()
        composeTestRule.onNodeWithText("Swap exercise").performClick()
        composeTestRule.onNodeWithText(target.name).performClick()

        assertEquals(listOf(0 to target.id), swaps)
    }

    /** The shortcut is the same mutation, not a second swap path that
     *  could drift from the sheet's. */
    @Test
    fun thePageMenuAndTheSheetSwapProduceTheSameMutation() {
        val swaps = mutableListOf<Pair<Int, String>>()
        val target = ExerciseCatalog.CODE_ONLY.substitutionsFor("bb_back_squat").first()
        setDayContent(dayEditActions = swapRecorder(swaps))

        // From the page menu: Swap exercise -> pick.
        composeTestRule.onNodeWithContentDescription("Barbell Back Squat menu").performClick()
        composeTestRule.onNodeWithText("Swap exercise").performClick()
        composeTestRule.onNodeWithText(target.name).performClick()

        // From the day menu: Edit day -> Edit -> Swap exercise -> pick.
        composeTestRule.onNodeWithContentDescription("Day menu").performClick()
        composeTestRule.onNodeWithText("Edit day").performClick()
        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithText("Swap exercise").performClick()
        composeTestRule.onNodeWithText(target.name).performClick()

        assertEquals(listOf(0 to target.id, 0 to target.id), swaps)
    }

    @Test
    fun aSlotWithNoResolvablePatternGetsNoSwapMenuItem() {
        val state = dayEditState.copy(
            slots = listOf(dayEditState.slots.single().copy(pattern = null)),
        )
        setDayContent(dayEditState = state)

        composeTestRule.onNodeWithContentDescription("Barbell Back Squat menu").performClick()
        assertTrue(
            composeTestRule.onAllNodesWithText("Swap exercise")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun setDayContent(
        dayState: DayUiState = this.dayState,
        dayEditState: DayEditUiState = this.dayEditState,
        dayEditActions: DayEditActions = swapRecorder(mutableListOf()),
    ) {
        composeTestRule.setContent {
            val editState = remember { mutableStateOf(dayEditState) }
            AppTheme {
                DayScreen(
                    state = dayState,
                    actions = dayActions(),
                    dayEditState = editState.value,
                    dayEditActions = dayEditActions.copy(onEditorOpen = { editState.value = editState.value.copy(editorOpen = it) }),
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
        onSelectPage = {},
        onKeepScreenOnChange = {},
        onClearChecks = {},
        onDone = {},
        onCreateExercise = {},
        onSetUpProgram = {},
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
