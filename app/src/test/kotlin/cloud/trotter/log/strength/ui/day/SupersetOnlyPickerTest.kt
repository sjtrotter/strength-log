package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SupersetOnlyPickerTest {
    @get:Rule val rule = createComposeRule()
    private val catalog = ExerciseCatalog.CODE_ONLY
    private val main = catalog.get("db_bench")
    private val partner = catalog.get("cable_row")
    private val slot = DayEditSlotState(1L, 0, main.id, main.name, main.pattern, true,
        partnerExerciseId = partner.id, partnerTitle = partner.name)

    private fun show(half: String = Slot.MAIN, enabled: Boolean = true, onOnly: (Int, String, Boolean, String, String) -> Unit = { _, _, _, _, _ -> }) {
        val state = DayEditUiState(slots = listOf(slot), catalog = catalog, workoutSetScreen = enabled)
        rule.setContent {
            AppTheme {
                SlotSwapSheet(state.performedSwapSlot(slot, half), requireNotNull(main.pattern), state, Color.White,
                    onSwap = { _, _ -> }, onDismiss = {}, onCreateExercise = {}, half = half, onOnly = onOnly)
            }
        }
    }

    @Test fun `MAIN picker has both only rows and defaults the choice to this workout`() {
        var selected: List<Any>? = null
        show { position, keep, workout, mainId, partnerId -> selected = listOf(position, keep, workout, mainId, partnerId) }
        rule.onAllNodesWithText("Drops the other half").assertCountEquals(2)
        rule.onNodeWithText("${partner.name} only").assertExists()
        rule.onNodeWithText("${main.name} only").performClick()
        rule.onNodeWithText("Apply").performClick()
        assertEquals(listOf(0, Slot.MAIN, true, main.id, partner.id), selected)
    }

    @Test fun `SS picker offers the same choices and can keep SS for the plan`() {
        var selected: List<Any>? = null
        show(half = Slot.SS) { position, keep, workout, mainId, partnerId -> selected = listOf(position, keep, workout, mainId, partnerId) }
        rule.onNodeWithText("${partner.name} only").performClick()
        rule.onNodeWithText("The plan").performClick()
        rule.onNodeWithText("Apply").performClick()
        assertEquals(listOf(0, Slot.SS, false, main.id, partner.id), selected)
    }

    @Test fun `flag off keeps the picker without only rows`() {
        show(enabled = false)
        rule.onNodeWithText("${main.name} only").assertDoesNotExist()
        rule.onNodeWithText("${partner.name} only").assertDoesNotExist()
    }
}
