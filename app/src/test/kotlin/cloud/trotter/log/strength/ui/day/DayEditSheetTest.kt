package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
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

    private fun show(
        canSetSuperset: Boolean,
        authored: Boolean = false,
        edit: Boolean = true,
        hasSlot: Boolean = true,
        hasCardio: Boolean = false,
        onRemoveCardio: () -> Unit = {},
        keepToPlan: Boolean = false,
        runOpen: Boolean = false,
        onKeepToPlan: (Long, Boolean) -> Unit = { _, _ -> },
        onResetSlot: (Long) -> Unit = {},
    ) {
        val slot = DayEditSlotState(
            programExerciseId = 1L,
            position = 0,
            exerciseId = "plank",
            title = "Plank",
            pattern = MovementPattern.CORE_ANTI_EXT,
            isSuperset = false,
            canSetSuperset = canSetSuperset,
            keepToPlan = keepToPlan,
        )
        composeTestRule.setContent {
            AppTheme {
                DayEditSheet(
                    state = DayEditUiState(
                        slots = if (hasSlot) listOf(slot) else emptyList(), authored = authored,
                        runOpen = runOpen,
                        cardioBlock = if (hasCardio) cardioFixture().block else null,
                    ),
                    actions = DayEditActions(
                        onSwap = { _, _ -> },
                        onAdd = {},
                        onRemove = {},
                        onSetSuperset = { _, _ -> },
                        onRemoveSuperset = {},
                        onResetToTemplate = {},
                        onRemoveCardio = onRemoveCardio,
                        onSetKeepToPlan = onKeepToPlan,
                        onResetSlotToPlan = onResetSlot,
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
    fun cardioOnlyDayDisablesRemoveCardio() {
        show(canSetSuperset = false, authored = true, edit = false, hasSlot = false, hasCardio = true)
        composeTestRule.onNodeWithText("Remove cardio").assertIsNotEnabled()
    }

    @Test
    fun dayWithAnExerciseCanRemoveCardio() {
        var removals = 0
        show(canSetSuperset = false, authored = true, edit = false, hasCardio = true,
            onRemoveCardio = { removals++ })
        composeTestRule.onNodeWithText("Remove cardio").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(1, removals) }
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

    @Test
    fun generatedSlotOffersNextTimeAndForwardsItsChoice() {
        var choice: Pair<Long, Boolean>? = null
        show(canSetSuperset = true, onKeepToPlan = { slot, keep -> choice = slot to keep })
        composeTestRule.onNodeWithText("Next time").assertExists()
        composeTestRule.onNodeWithText("Last results").assertExists()
        composeTestRule.onNodeWithText("Plan").performClick()
        composeTestRule.runOnIdle { assertEquals(1L to true, choice) }
    }

    @Test
    fun authoredSlotOffersNextTimeAndReset() {
        show(canSetSuperset = false, authored = true)
        composeTestRule.onNodeWithText("Last results").assertExists()
        composeTestRule.onNodeWithText("Plan").assertExists()
        composeTestRule.onNodeWithText("Reset to plan").assertExists()
    }

    @Test
    fun resetToPlanAsksBeforeForwardingTheSlot() {
        var resetSlot: Long? = null
        show(canSetSuperset = true, onResetSlot = { resetSlot = it })
        composeTestRule.onNodeWithText("Reset to plan").performClick()
        composeTestRule.onNodeWithText("Reset to plan?").assertExists()
        composeTestRule.runOnIdle { assertEquals(null, resetSlot) }
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.runOnIdle { assertEquals(null, resetSlot) }
        composeTestRule.onNodeWithText("Reset to plan").performClick()
        composeTestRule.onNodeWithText("Reset").performClick()
        composeTestRule.runOnIdle { assertEquals(1L, resetSlot) }
    }

    @Test
    fun keptSlotHidesResetToPlan() {
        show(canSetSuperset = true, keepToPlan = true)
        composeTestRule.onNodeWithText("Reset to plan").assertDoesNotExist()
    }

    @Test
    fun openDayHidesResetToPlan() {
        show(canSetSuperset = true, runOpen = true)
        composeTestRule.onNodeWithText("Reset to plan").assertDoesNotExist()
    }
}
