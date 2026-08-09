package cloud.trotter.log.strength.ui.customexercise

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the footer's post-M3 contract (PR #185): SAVE's enablement is
 * M3-owned (a nameless exercise cannot be saved), and CANCEL always fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CustomExerciseFooterTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(state: CustomExerciseUiState, onSave: () -> Unit = {}, onCancel: () -> Unit = {}) {
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
                        onWeightChange = {},
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
        composeTestRule.onNodeWithText("SAVE").assertIsNotEnabled()
    }

    @Test
    fun saveFiresOnceNamed() {
        var saves = 0
        setContent(CustomExerciseUiState(name = "Zercher Squat"), onSave = { saves++ })
        composeTestRule.onNodeWithText("SAVE").assertIsEnabled().performClick()
        assertEquals(1, saves)
    }

    @Test
    fun cancelAlwaysFires() {
        var cancels = 0
        setContent(CustomExerciseUiState(), onCancel = { cancels++ })
        composeTestRule.onNodeWithText("CANCEL").performClick()
        assertEquals(1, cancels)
    }
}
