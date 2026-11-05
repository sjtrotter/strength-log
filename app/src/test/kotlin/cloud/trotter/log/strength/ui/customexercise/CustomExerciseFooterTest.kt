package cloud.trotter.log.strength.ui.customexercise

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the footer's contract: Save requires a name, pattern and valid active
 * target drafts, and Cancel always fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CustomExerciseFooterTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        state: CustomExerciseUiState,
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onWeightChange: (Double) -> Unit = {},
    ) {
        composeTestRule.setContent {
            AppTheme {
                CustomExerciseScreen(
                    state,
                    CustomExerciseActions(
                        onNameChange = {},
                        onPatternChange = {},
                        onEquipmentToggle = {},
                        onPerHandChange = {},
                        onTrackingChange = {},
                        onWeightChange = onWeightChange,
                        onTargetRepsChange = {},
                        onTargetSecondsChange = {},
                        onAddedWeightChange = {},
                        onSave = onSave,
                        onCancel = onCancel,
                    ),
                )
            }
        }
    }

    @Test
    fun saveIsDisabledUntilTheExerciseHasAName() {
        setContent(CustomExerciseUiState(name = "  "))
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Name the exercise to save it.").assertExists()
        composeTestRule.onNodeWithText("Enter an exercise name.").assertExists()
    }

    @Test
    fun saveFiresOnceNamed() {
        var saves = 0
        setContent(CustomExerciseUiState(name = "Zercher Squat", pattern = MovementPattern.SQUAT_BILATERAL), onSave = { saves++ })
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(1, saves)
    }

    @Test
    fun blankWeightBlocksSaveUntilCorrectedAndCommitsBeforeSaving() {
        val events = mutableListOf<String>()
        setContent(
            CustomExerciseUiState(name = "Zercher Squat", pattern = MovementPattern.SQUAT_BILATERAL),
            onSave = { events += "save" },
            onWeightChange = { events += "weight $it" },
        )
        composeTestRule.onNodeWithText("Starting weight (lb)")
            .performScrollTo().performClick().performTextReplacement("")

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("Enter a weight to save it").assertExists()
        composeTestRule.runOnIdle { assertEquals(emptyList<String>(), events) }

        composeTestRule.onNodeWithText("Starting weight (lb)").performClick().performTextReplacement("50")
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(listOf("weight 50.0", "save"), events) }
    }

    @Test
    fun invalidWeightBlocksSave() {
        assertTargetBlocksSave(TrackingType.WEIGHTED, "Starting weight (lb)", "-1", "Enter a weight to save it")
    }

    @Test
    fun invalidRepsBlockSave() {
        assertTargetBlocksSave(TrackingType.REPS, "Target reps", "0", "Enter target reps to save it")
    }

    @Test
    fun invalidHoldBlocksSave() {
        assertTargetBlocksSave(TrackingType.TIMED, "Target hold (seconds)", "0", "Enter a hold time to save it")
    }

    @Test
    fun blankAddedLoadBlocksSaveEvenWhenTheHoldIsValid() {
        assertTargetBlocksSave(TrackingType.TIMED, "Added load, optional (lb)", "", "Enter an added load to save it")
    }

    private fun assertTargetBlocksSave(tracking: TrackingType, label: String, draft: String, message: String) {
        var saves = 0
        setContent(
            CustomExerciseUiState(name = "Custom exercise", pattern = MovementPattern.SQUAT_BILATERAL, tracking = tracking),
            onSave = { saves++ },
        )
        composeTestRule.onNodeWithText(label).performScrollTo().performClick().performTextReplacement(draft)
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText(message).assertExists()
        composeTestRule.onNodeWithText("Name").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.runOnIdle { assertEquals(0, saves) }
    }

    @Test
    fun saveRequiresAnExplicitPatternChoiceFromTheDialog() {
        val state = mutableStateOf(CustomExerciseUiState(name = "Zercher Squat"))
        var savedPattern: MovementPattern? = null
        composeTestRule.setContent { AppTheme {
            CustomExerciseScreen(state.value, CustomExerciseActions(
                onNameChange = {}, onPatternChange = { state.value = state.value.copy(pattern = it) },
                onEquipmentToggle = {}, onPerHandChange = {}, onTrackingChange = {}, onWeightChange = {},
                onTargetRepsChange = {}, onTargetSecondsChange = {}, onAddedWeightChange = {},
                onSave = { savedPattern = state.value.pattern }, onCancel = {},
            ))
        } }
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Choose a movement pattern to save it").assertExists()
        composeTestRule.onNodeWithText("Choose a movement pattern").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Squat (bilateral)").performClick()
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(MovementPattern.SQUAT_BILATERAL, savedPattern)
    }

    @Test
    fun cancelAlwaysFires() {
        var cancels = 0
        setContent(CustomExerciseUiState(), onCancel = { cancels++ })
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun headerCancelAnnouncesItsActionAndFires() {
        var cancels = 0
        setContent(CustomExerciseUiState(), onCancel = { cancels++ })

        composeTestRule.onNodeWithContentDescription("Cancel").performClick()

        assertEquals(1, cancels)
    }
}
