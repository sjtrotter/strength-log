package cloud.trotter.log.strength.ui.plan

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.ui.setup.GoalPreviewItem
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.CardioActivity
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

    private fun actions() = PlanActions(onOpenDay = {}, onOpenPlans = {}, onSetUpProgram = {})

    @Test
    fun generatedGoalsAndEmphasisAreVisibleAndTheWholeRowSelects() {
        var emphasis: GoalEmphasis? = null
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture().copy(
                goals = listOf(GoalPreviewItem("Barbell Back Squat", "235", false)),
                emphasis = GoalEmphasis.BALANCED,
            ), actions().copy(onEmphasisChange = { emphasis = it }))
        } }
        val list = rule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Goals"))
        rule.onNodeWithText("Goals").assertExists()
        rule.onNodeWithText("235").assertExists()
        list.performScrollToNode(hasText("Physique-leaning"))
        rule.onNodeWithText("Strength-leaning").assertExists()
        rule.onNodeWithText("Balanced strength + muscle").assertIsSelected()
        rule.onNodeWithText("Physique-leaning").assertExists()
        rule.onNodeWithText("Strength-leaning").performClick()
        assertEquals(GoalEmphasis.STRENGTH, emphasis)
    }

    @Test
    fun authoredPlansHaveNoGoalsOrEmphasis() {
        rule.setContent { AppTheme { PlanScreen(planScreenFixture(), actions()) } }
        rule.onNodeWithText("Goals").assertDoesNotExist()
        rule.onNodeWithText("Emphasis").assertDoesNotExist()
        rule.onNodeWithText("Strength-leaning").assertDoesNotExist()
    }

    @Test
    fun dayRowCarriesTheDisplayedPlanAndDay() {
        val selected = mutableListOf<DaySelection>()
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture().copy(planId = 7L, days = listOf(
                PlanDay("A", 0, "Squat", 1, 3, true),
            )), actions().copy(onOpenDay = { selected += it }))
        } }

        rule.onNodeWithText("Squat").performClick()

        assertEquals(listOf(DaySelection(7L, "A")), selected)
    }

    @Test
    fun cardioOnlyDayShowsTheActivityAndSetCount() {
        val state = planScreenFixture().copy(days = listOf(
            PlanDay("A", 0, "Run", 0, 0, true, cardioActivity = CardioActivity.ROAD_RUN, cardioSetCount = 5),
        ))
        rule.setContent { AppTheme { PlanScreen(state, actions()) } }
        rule.onNodeWithText("Road run · 5 sets").assertExists()
        rule.onNodeWithText("0 lifts, 0 sets").assertDoesNotExist()
    }

    @Test
    fun authoredPlanOffersDayActionsAndHidesRemovalForTheLastDay() {
        val renamed = mutableListOf<String>()
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture(menu = "A"), actions().copy(onRenameDay = { renamed += it }))
        } }
        rule.onNodeWithText("Rename").performClick()
        assertEquals(listOf("A"), renamed)
        rule.onNodeWithText("Remove").assertDoesNotExist()
        // The controlled menu remains open until its action updates state.
        rule.onNodeWithText("Add day").assertExists()
    }

    @Test
    fun generatedPlanOffersDayMenuAndAddAction() {
        rule.setContent { AppTheme { PlanScreen(planScreenFixture(), actions()) } }
        rule.onNodeWithText("Add day").assertExists()
        rule.onNodeWithContentDescription("Day options").assertExists()
        rule.onNodeWithText("Next").assertExists()
    }

    @Test
    fun sevenDaysHideAddDay() {
        rule.setContent { AppTheme { PlanScreen(planScreenFixture(count = 7), actions()) } }
        rule.onNodeWithText("Add day").assertDoesNotExist()
    }

    @Test
    fun authoredPlanOffersRemovalWhenAnotherDayRemains() {
        val removed = mutableListOf<String>()
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture(count = 2, menu = "A"), actions().copy(onRemoveDay = { removed += it }))
        } }
        rule.onNodeWithText("Remove").performClick()
        assertEquals(listOf("A"), removed)
    }

    private fun mixedState(strengthCount: Int = 1, menu: String? = null): PlanUiState {
        val strength = (0 until strengthCount).map { index ->
            val id = ('A' + index).toString()
            ProgramDay(id, "Day $id", "", emptyList())
        }
        val cardio = listOf("C1", "C2").map { id ->
            ProgramDay(id, "Day $id", "", emptyList(), kind = ProgramDayKind.CARDIO)
        }
        return planScreenFixture(menu = menu).copy(days = PlanScreenBuilder.days(
            Program(strength + cardio), "A", LifterConfig(), ExerciseCatalog.CODE_ONLY,
        ))
    }

    @Test
    fun strengthDayOffersRemoveWhenOnlyCardioDaysRemain() {
        val removed = mutableListOf<String>()
        rule.setContent { AppTheme {
            PlanScreen(mixedState(menu = "A"), actions().copy(onRemoveDay = { removed += it }))
        } }
        rule.onNodeWithText("Remove").performClick()
        assertEquals(listOf("A"), removed)
    }

    @Test
    fun standaloneCardioDayOffersRenameAndRemove() {
        val renamed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        rule.setContent { AppTheme {
            PlanScreen(mixedState(menu = "C1"), actions().copy(
                onRenameDay = { renamed += it }, onRemoveDay = { removed += it },
            ))
        } }
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithText("Remove").performClick()
        assertEquals(listOf("C1"), renamed)
        assertEquals(listOf("C1"), removed)
    }

    @Test
    fun sixStrengthDaysAndTwoCardioDaysStillOfferAddDay() {
        rule.setContent { AppTheme { PlanScreen(mixedState(strengthCount = 6), actions()) } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Add day"))
        rule.onNodeWithText("Add day").assertExists()
    }

    @Test
    fun removalExplainsWhatIsDeletedAndThatHistoryIsKept() {
        rule.setContent { AppTheme {
            PlanScreen(planScreenFixture(count = 2).copy(dialog = "remove", dialogDayId = "B", dayTitleDraft = "Day B"), actions())
        } }
        rule.onNodeWithText("Remove Day B and its exercises? History is kept.").assertExists()
    }
}
