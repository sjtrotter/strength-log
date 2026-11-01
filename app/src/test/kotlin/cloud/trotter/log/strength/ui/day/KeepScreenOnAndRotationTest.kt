package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Finish stays reachable at enlarged font scale, and set-edit identity survives rotation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class KeepScreenOnAndRotationTest {

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
                isMain = false,
                isSuperset = false,
                hasWarmupHint = false,
                headerTarget = HeaderTarget.Goal("235"),
                perHand = false,
                allDone = false,
                rows = listOf(
                    // Ticked: the label under test is the all-done one.
                    SetRowState(0, "R1", isTop = false, weightDisplay = 130.0, reps = 5, done = true),
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

    @Test
    fun finishRemainsReachableAtDoubleFontScale() {
        setDayContent(fontScale = 2f)
        composeTestRule.onNodeWithText("Finish").assertIsDisplayed()
    }

    @Test
    fun theOpenSetIdentitySurvivesRotation() {
        val restoration = StateRestorationTester(composeTestRule)
        val state = mutableStateOf(workoutDayFixture())
        val actions = workoutActions().copy(onOpenSetEditor = { id, index ->
            state.value = state.value.copy(openSheet = OpenSheet("D", OpenSheet.Kind.EDITOR, id, index))
        })
        restoration.setContent { AppTheme { DayScreen(state.value, actions, DayEditUiState(), workoutEditActions()) } }
        composeTestRule.onNodeWithContentDescription("Edit set 2").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("Weight, lb").assertExists()
        composeTestRule.onNodeWithText("Done").assertExists()
    }

    private fun setDayContent(
        dayState: DayUiState = this.dayState,
        fontScale: Float = 1f,
    ) {
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppTheme {
                    DayScreen(
                        state = dayState.withWorkoutSelection(),
                        actions = dayActions(),
                        dayEditState = dayEditState,
                        dayEditActions = noopEditActions(),
                    )
                }
            }
        }
    }

    private fun dayActions() = DayActions(
        onWeightChange = { _, _, _, _ -> },
        onRepsChange = { _, _, _, _ -> },
        onSecondsChange = { _, _, _, _ -> },
        onToggleDone = { _, _, _, _ -> },
        onAddSet = { _, _ -> },
        onRemoveSet = { _, _, _ -> },
        onDiscardWorkout = {},
        onDone = {},
        onCreateExercise = {},
        onSetUpProgram = {},
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
