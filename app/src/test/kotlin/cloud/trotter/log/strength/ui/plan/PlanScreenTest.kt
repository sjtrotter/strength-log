package cloud.trotter.log.strength.ui.plan

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class PlanScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun state(authored: Boolean, count: Int = 1, menu: String? = null) = PlanUiState(
        name = "Prep", hasProgram = true, authored = authored, menuDayId = menu,
        days = (0 until count).map { index ->
            val letter = ('A' + index).toString()
            PlanDay(letter, index, "Day $letter", 0, 0, index == 0)
        },
    )
    private fun actions() = PlanActions(onOpenDay = {}, onOpenPlans = {}, onSetUpProgram = {})

    @Test
    fun authoredPlanOffersDayActionsAndHidesRemovalForTheLastDay() {
        val renamed = mutableListOf<String>()
        rule.setContent { AppTheme {
            PlanScreen(state(true, menu = "A"), actions().copy(onRenameDay = { renamed += it }))
        } }
        rule.onNodeWithText("Rename").performClick()
        assertEquals(listOf("A"), renamed)
        rule.onNodeWithText("Remove").assertDoesNotExist()
        // The controlled menu remains open until its action updates state.
        rule.onNodeWithText("Add day").assertExists()
    }

    @Test
    fun generatedPlanHasNoDayMenuOrAddAction() {
        rule.setContent { AppTheme { PlanScreen(state(false), actions()) } }
        rule.onNodeWithText("Add day").assertDoesNotExist()
        rule.onNodeWithContentDescription("Day options").assertDoesNotExist()
        rule.onNodeWithText("Next").assertExists()
    }

    @Test
    fun sevenDaysHideAddDay() {
        rule.setContent { AppTheme { PlanScreen(state(true, count = 7), actions()) } }
        rule.onNodeWithText("Add day").assertDoesNotExist()
    }

    @Test
    fun authoredPlanOffersRemovalWhenAnotherDayRemains() {
        val removed = mutableListOf<String>()
        rule.setContent { AppTheme {
            PlanScreen(state(true, count = 2, menu = "A"), actions().copy(onRemoveDay = { removed += it }))
        } }
        rule.onNodeWithText("Remove").performClick()
        assertEquals(listOf("A"), removed)
    }

    @Test
    fun removalExplainsWhatIsDeletedAndThatHistoryIsKept() {
        rule.setContent { AppTheme {
            PlanScreen(state(true, count = 2).copy(dialog = "remove", dialogDayId = "B", dayTitleDraft = "Day B"), actions())
        } }
        rule.onNodeWithText("Remove Day B and its exercises? History is kept.").assertExists()
    }
}
