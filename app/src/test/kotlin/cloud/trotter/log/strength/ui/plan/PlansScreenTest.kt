package cloud.trotter.log.strength.ui.plan

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.PlanSummary
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertTrue
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

    private fun actions() = PlansActions(
        onNewPlan = {}, onSelect = {}, onMenu = {},
        onRename = {}, onRenameDraft = {}, onSaveRename = {}, onDismissRename = {},
        onDuplicate = {}, onArchive = {}, onRestore = {},
    )

    @Test
    fun settingsGearOpensSettings() {
        var opened = false
        composeTestRule.setContent { AppTheme {
            PlansScreen(PlansUiState(), actions().copy(onOpenSettings = { opened = true }))
        } }
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assertTrue(opened)
    }

    @Test
    fun pausedRowMenuOffersMembershipAndPreferenceActions() {
        var alongside: Long? = null
        var only: Long? = null
        var preferred: Long? = null
        composeTestRule.setContent { AppTheme {
            PlansScreen(PlansUiState(plans = listOf(
                PlanSummary(2, "Other", "A", "Squat", false, false, null),
            ), menuPlanId = 2), actions().copy(
                onUseAlongside = { alongside = it }, onUseOnly = { only = it }, onSelect = { preferred = it },
            ))
        } }
        composeTestRule.onNodeWithText("Use alongside").performClick()
        composeTestRule.onNodeWithText("Use only this plan").performClick()
        composeTestRule.onNodeWithText("Make preferred").performClick()
        assertEquals(2L, alongside)
        assertEquals(2L, only)
        assertEquals(2L, preferred)
    }

    @Test
    fun preferredRowCanPauseTheLastPlanOnToday() {
        var paused: Long? = null
        composeTestRule.setContent { AppTheme {
            PlansScreen(PlansUiState(plans = listOf(
                PlanSummary(1, "Preferred", "A", "Squat", true, false, null, onToday = true),
            ), menuPlanId = 1), actions().copy(onPause = { paused = it }))
        } }
        composeTestRule.onNodeWithText("On Today", substring = true).assertExists()
        composeTestRule.onNodeWithText("Pause").performClick()
        assertEquals(1L, paused)
    }

    @Test
    fun newPlanOpensTheChoiceDialog() {
        var opened = false
        composeTestRule.setContent { AppTheme {
            PlansScreen(PlansUiState(), actions().copy(onShowNewPlan = { opened = true }))
        } }
        composeTestRule.onNodeWithText("New plan").performClick()
        assertTrue(opened)
    }

    @Test
    fun newPlanChoiceOffersWizardAndEmpty() {
        var startedEmpty = false
        composeTestRule.setContent { AppTheme {
            PlansScreen(PlansUiState(newPlanPage = "choice"), actions().copy(onStartEmpty = { startedEmpty = true }))
        } }
        composeTestRule.onNodeWithText("Generate with the wizard").assertExists()
        composeTestRule.onNodeWithText("Start empty").performClick()
        assertTrue(startedEmpty)
    }

    @Test
    fun planRowOpensItsDaysAndOverflowRemainsASeparateAction() {
        val selected = mutableListOf<Long>()
        val menus = mutableListOf<Long?>()
        composeTestRule.setContent {
            AppTheme {
                PlansScreen(
                    PlansUiState(plans = listOf(
                        PlanSummary(1, "Full body", "A", "Squat", selected = true, archived = false, lastSessionAt = null),
                    )),
                    PlansActions(
                        onNewPlan = {}, onSelect = {}, onOpenPlan = { selected += it }, onMenu = { menus += it },
                        onRename = {}, onRenameDraft = {}, onSaveRename = {}, onDismissRename = {},
                        onDuplicate = {}, onArchive = {}, onRestore = {},
                    ),
                )
            }
        }

        val row = composeTestRule.onNodeWithText("Full body")
        row.assertHasClickAction()
        composeTestRule.onNodeWithContentDescription("Plan options")
            .assertHasClickAction()
            .performClick()
        assertEquals(listOf(1L), menus)
        assertEquals(emptyList<Long>(), selected)

        row.performClick()
        assertEquals(listOf(1L), selected)
    }
}
