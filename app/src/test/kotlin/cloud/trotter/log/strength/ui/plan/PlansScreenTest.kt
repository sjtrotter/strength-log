package cloud.trotter.log.strength.ui.plan

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.PlanSummary
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class PlansScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun planNameLabelsTheRadioRowAndOverflowRemainsASeparateAction() {
        val selected = mutableListOf<Long>()
        val menus = mutableListOf<Long?>()
        composeTestRule.setContent {
            AppTheme {
                PlansScreen(
                    PlansUiState(plans = listOf(
                        PlanSummary(1, "Full body", "A", "Squat", selected = true, archived = false, lastSessionAt = null),
                    )),
                    PlansActions(
                        onBack = {}, onNewPlan = {}, onSelect = { selected += it }, onMenu = { menus += it },
                        onRename = {}, onRenameDraft = {}, onSaveRename = {}, onDismissRename = {},
                        onDuplicate = {}, onArchive = {}, onRestore = {},
                    ),
                )
            }
        }

        val row = composeTestRule.onNodeWithText("Full body")
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
            .assertHasClickAction()
        composeTestRule.onNodeWithContentDescription("Plan options")
            .assertHasClickAction()
            .performClick()
        assertEquals(listOf(1L), menus)
        assertEquals(emptyList<Long>(), selected)

        row.performClick()
        assertEquals(listOf(1L), selected)
    }
}
