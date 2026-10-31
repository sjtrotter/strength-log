package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.PrescribedTarget
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
        canResetToTemplate: Boolean = false,
        prescription: Prescription? = null,
        partnerExerciseId: String? = null,
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
            isSuperset = partnerExerciseId != null,
            partnerExerciseId = partnerExerciseId,
            prescription = prescription,
            keepToPlan = keepToPlan,
        )
        composeTestRule.setContent {
            AppTheme {
                DayEditSheet(
                    state = DayEditUiState(
                        slots = if (hasSlot) listOf(slot) else emptyList(), canResetToTemplate = canResetToTemplate,
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
    fun generatedDayOffersAddCardio() {
        show(edit = false)
        composeTestRule.onNodeWithText("Add cardio").assertExists()
    }

    @Test
    fun generatedDayOffersEditAndRemoveCardio() {
        var removals = 0
        show(edit = false, hasCardio = true, onRemoveCardio = { removals++ })
        composeTestRule.onNodeWithText("Edit cardio").assertExists()
        composeTestRule.onNodeWithText("Remove cardio").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(1, removals) }
    }

    @Test
    fun cardioOnlyDayDisablesRemoveCardio() {
        show(edit = false, hasSlot = false, hasCardio = true)
        composeTestRule.onNodeWithText("Remove cardio").assertIsNotEnabled()
    }

    @Test
    fun dayWithAnExerciseCanRemoveCardio() {
        var removals = 0
        show(edit = false, hasCardio = true,
            onRemoveCardio = { removals++ })
        composeTestRule.onNodeWithText("Remove cardio").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(1, removals) }
    }

    @Test
    fun prescribedSlotOffersPrescriptionMovesAndSupersets() {
        show(prescription = Prescription(PrescribedTarget.Reps(12)))
        composeTestRule.onNodeWithText("Prescription").assertExists()
        composeTestRule.onNodeWithText("Move up").assertExists()
        composeTestRule.onNodeWithText("Move down").assertExists()
        composeTestRule.onNodeWithText("Add superset").assertExists()
    }

    @Test
    fun authoredDayHidesResetToTemplate() {
        show(edit = false)
        composeTestRule.onNodeWithText("Reset day to template").assertDoesNotExist()
    }

    @Test
    fun generatedDayKeepsResetToTemplate() {
        show(canResetToTemplate = true, edit = false)
        composeTestRule.onNodeWithText("Reset day to template").assertExists()
    }

    @Test
    fun prescribedPairOffersSwapAndRemoveSuperset() {
        show(prescription = Prescription(PrescribedTarget.Reps(12)), partnerExerciseId = "pushup")
        composeTestRule.onNodeWithText("Swap superset partner").assertExists()
        composeTestRule.onNodeWithText("Remove superset").assertExists()
    }

    @Test
    fun generatedSlotOffersAddSuperset() {
        show()
        composeTestRule.onNodeWithText("Prescription").assertExists()
        composeTestRule.onNodeWithText("Move up").assertExists()
        composeTestRule.onNodeWithText("Move down").assertExists()
        composeTestRule.onNodeWithText("Add superset").assertExists()
    }

    @Test
    fun generatedSlotOffersNextTimeAndForwardsItsChoice() {
        var choice: Pair<Long, Boolean>? = null
        show(onKeepToPlan = { slot, keep -> choice = slot to keep })
        composeTestRule.onNodeWithText("Next time").assertExists()
        composeTestRule.onNodeWithText("Last results").assertExists()
        composeTestRule.onNodeWithText("Plan").performClick()
        composeTestRule.runOnIdle { assertEquals(1L to true, choice) }
    }

    @Test
    fun authoredSlotOffersNextTimeAndReset() {
        show()
        composeTestRule.onNodeWithText("Last results").assertExists()
        composeTestRule.onNodeWithText("Plan").assertExists()
        composeTestRule.onNodeWithText("Reset to plan").assertExists()
    }

    @Test
    fun resetToPlanAsksBeforeForwardingTheSlot() {
        var resetSlot: Long? = null
        show(onResetSlot = { resetSlot = it })
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
        show(keepToPlan = true)
        composeTestRule.onNodeWithText("Reset to plan").assertDoesNotExist()
    }

    @Test
    fun openDayHidesResetToPlan() {
        show(runOpen = true)
        composeTestRule.onNodeWithText("Reset to plan").assertDoesNotExist()
    }
}
