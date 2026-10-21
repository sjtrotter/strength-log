package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class DayEditSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(canSetSuperset: Boolean, authored: Boolean = false, edit: Boolean = true) {
        val slot = DayEditSlotState(
            programExerciseId = 1L,
            position = 0,
            exerciseId = "plank",
            title = "Plank",
            pattern = MovementPattern.CORE_ANTI_EXT,
            isSuperset = false,
            canSetSuperset = canSetSuperset,
        )
        composeTestRule.setContent {
            AppTheme {
                DayEditSheet(
                    state = DayEditUiState(slots = listOf(slot), authored = authored),
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
        if (edit) composeTestRule.onNodeWithText("Edit").performClick()
    }

    @Test
    fun authoredSlotOffersPrescriptionAndMovesWithoutSupersets() {
        show(canSetSuperset = true, authored = true)
        composeTestRule.onNodeWithText("Prescription").assertExists()
        composeTestRule.onNodeWithText("Move up").assertExists()
        composeTestRule.onNodeWithText("Move down").assertExists()
        composeTestRule.onNodeWithText("Add superset").assertDoesNotExist()
    }

    @Test
    fun authoredDayHidesResetToTemplate() {
        show(canSetSuperset = false, authored = true, edit = false)
        composeTestRule.onNodeWithText("Reset day to template").assertDoesNotExist()
    }

    @Test
    fun generatedDayKeepsResetToTemplate() {
        show(canSetSuperset = true, edit = false)
        composeTestRule.onNodeWithText("Reset day to template").assertExists()
    }

    @Test
    fun prescribedSlotHidesAddSuperset() {
        show(canSetSuperset = false)
        composeTestRule.onNodeWithText("Swap exercise").assertExists()
        composeTestRule.onNodeWithText("Add superset").assertDoesNotExist()
    }

    @Test
    fun generatedSlotOffersAddSuperset() {
        show(canSetSuperset = true)
        composeTestRule.onNodeWithText("Add superset").assertExists()
    }
}
