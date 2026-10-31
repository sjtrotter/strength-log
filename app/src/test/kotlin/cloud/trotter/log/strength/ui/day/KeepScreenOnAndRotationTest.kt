package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The menu reflects the persisted preference; swap and set-edit selections survive rotation. */
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
    fun theMenuRendersThePreferenceAndReportsTapsOutward() {
        val taps = mutableListOf<Boolean>()
        setDayContent(dayState = dayState.copy(keepScreenOn = true), onKeepScreenOnChange = { taps += it })

        composeTestRule.onNodeWithContentDescription("Day menu").performClick()
        composeTestRule.onNodeWithText("Keep screen on").performClick()

        // The menu owns no copy of the state: it asked for OFF and stayed ON,
        // because only the persisted preference can turn it off.
        assertEquals(listOf(false), taps)
        composeTestRule.onNodeWithContentDescription("Day menu").performClick()
        composeTestRule.onNodeWithText("Keep screen on").assertIsOn()
    }

    @Test
    fun theMenuReadsOffWhenThePreferenceIsOff() {
        setDayContent()
        composeTestRule.onNodeWithContentDescription("Day menu").performClick()
        composeTestRule.onNodeWithText("Keep screen on").assertIsOff()
    }

    @Test
    fun finishRemainsReachableAtDoubleFontScale() {
        setDayContent(fontScale = 2f)
        composeTestRule.onNodeWithText("Finish day A").assertIsDisplayed()
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

        composeTestRule.onNodeWithContentDescription("Barbell Back Squat menu").performClick()
        composeTestRule.onNodeWithText("Add weight").performClick()
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

        composeTestRule.onNodeWithContentDescription("Barbell Back Squat menu").performClick()
        composeTestRule.onNodeWithText("Add weight").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("Switch").performClick()

        assertEquals(listOf(0 to "weighted_plank"), swaps)
    }

    @Test
    fun theOpenSetIdentitySurvivesRotation() {
        val restoration = StateRestorationTester(composeTestRule)
        val state = mutableStateOf(pagerFixture())
        val actions = pagerActions().copy(onOpenSetEditor = { id, index ->
            state.value = state.value.copy(openSheet = OpenSheet("D", OpenSheet.Kind.EDITOR, id, index))
        })
        restoration.setContent { AppTheme { DayScreen(state.value, actions, DayEditUiState(), pagerEditActions()) } }
        composeTestRule.onNodeWithText("95 × 5").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("Weight, lb").assertExists()
        composeTestRule.onNodeWithText("Done").assertExists()
    }

    private fun setDayContent(
        dayState: DayUiState = this.dayState,
        onKeepScreenOnChange: (Boolean) -> Unit = {},
        fontScale: Float = 1f,
    ) {
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
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
    }

    private fun dayActions(onKeepScreenOnChange: (Boolean) -> Unit = {}) = DayActions(
        onSelectDay = {},
        onWeightChange = { _, _, _, _ -> },
        onRepsChange = { _, _, _, _ -> },
        onSecondsChange = { _, _, _, _ -> },
        onToggleDone = { _, _, _, _ -> },
        onAddSet = { _, _ -> },
        onRemoveSet = { _, _, _ -> },
        onSelectPage = {},
        onKeepScreenOnChange = onKeepScreenOnChange,
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
