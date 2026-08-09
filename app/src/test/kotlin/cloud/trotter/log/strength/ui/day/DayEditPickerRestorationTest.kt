package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class DayEditPickerRestorationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val state = DayEditUiState(
        catalog = ExerciseCatalog.CODE_ONLY,
        slots = listOf(
            DayEditSlotState(
                programExerciseId = 1,
                position = 0,
                exerciseId = "bb_back_squat",
                title = "Barbell Back Squat",
                pattern = MovementPattern.SQUAT_BILATERAL,
                isSuperset = false,
            ),
        ),
    )

    @Test
    fun swapPickerDraftSurvivesRestoration() {
        val restoration = showSheet()
        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithText("Swap exercise").performClick()
        assertDraftSurvives(restoration)
    }

    @Test
    fun addPickerDraftSurvivesRestoration() {
        val restoration = showSheet()
        composeTestRule.onNodeWithText("+ Add exercise").performClick()
        composeTestRule.onNodeWithText("Squat bilateral").performClick()
        assertDraftSurvives(restoration)
    }

    @Test
    fun supersetPickerDraftSurvivesRestoration() {
        val restoration = showSheet()
        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithText("Add superset").performClick()
        composeTestRule.onNodeWithText("Squat bilateral").performClick()
        assertDraftSurvives(restoration)
    }

    private fun showSheet(): StateRestorationTester {
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            AppTheme {
                DayEditSheet(
                    state = state,
                    actions = DayEditActions(
                        onSwap = { _, _ -> },
                        onAdd = {},
                        onRemove = {},
                        onSetSuperset = { _, _ -> },
                        onRemoveSuperset = {},
                        onResetToTemplate = {},
                    ),
                    accent = Color.White,
                    onDismiss = {},
                    onCreateExercise = {},
                )
            }
        }
        return restoration
    }

    private fun assertDraftSurvives(restoration: StateRestorationTester) {
        composeTestRule.onNode(hasSetTextAction()).performTextInput("press")
        composeTestRule.onNodeWithText("Barbell").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNode(hasSetTextAction()).assertTextContains("press")
        composeTestRule.onNodeWithText("Barbell").assertIsOff()
    }
}
