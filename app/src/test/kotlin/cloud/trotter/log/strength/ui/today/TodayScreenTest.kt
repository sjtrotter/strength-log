package cloud.trotter.log.strength.ui.today

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.OpenRun
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.day.WorkoutConflict
import cloud.trotter.log.strength.ui.day.WorkoutConflictDialog
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TodayScreenTest {
    @get:Rule val rule = createComposeRule()
    private val first = TodayDay(1, "Strength", "B", 1, "Squat")
    private val second = TodayDay(2, "Prep", "A", 0, "Upper")
    private fun actions() = TodayActions(onStart = {}, onOpenSettings = {}, onSetUpProgram = {})

    @Test fun nothingOnTodayOpensPlans() {
        var opened = false
        rule.setContent { AppTheme {
            TodayScreen(TodayUiState(nothingOnToday = true), actions().copy(onOpenPlans = { opened = true }))
        } }
        rule.onNodeWithText("Nothing on Today").assertExists()
        rule.onNodeWithText("Plans").performClick()
        assertTrue(opened)
    }

    @Test fun upNextRowsOpenPreviewAndOnlyDisplayRowHasStart() {
        val previews = mutableListOf<DaySelection>()
        val starts = mutableListOf<DaySelection>()
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, upNext = listOf(first, second)),
            actions().copy(onOpenPreview = { previews += it }, onStart = { starts += it }),
        ) } }
        val firstRow = rule.onNodeWithText("Strength · Day B").fetchSemanticsNode().boundsInRoot
        val secondRow = rule.onNodeWithText("Prep · Day A").fetchSemanticsNode().boundsInRoot
        assertTrue(firstRow.top < secondRow.top)
        rule.onAllNodes(androidx.compose.ui.test.hasText("Start")).assertCountEquals(1)
        rule.onNodeWithText("Prep · Day A").performClick()
        rule.onNodeWithText("Start").performClick()
        assertEquals(listOf(second.selection), previews)
        assertEquals(listOf(first.selection), starts)
    }

    @Test fun resumeIsFirstAndOpensTheRunPlanAndDay() {
        var resumed: DaySelection? = null
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, resume = second, upNext = listOf(first)),
            actions().copy(onResume = { resumed = it }),
        ) } }
        val resume = rule.onNodeWithText("Resume").fetchSemanticsNode().boundsInRoot
        val upNext = rule.onNodeWithText("Up next").fetchSemanticsNode().boundsInRoot
        assertTrue(resume.top < upNext.top)
        rule.onNodeWithText("Resume").performClick()
        assertEquals(second.selection, resumed)
    }

    @Test fun pausedOpenRunKeepsNothingOnTodayAndPlansBelowResumeWithoutUpNextHeader() {
        var opened = false
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, nothingOnToday = true, resume = second),
            actions().copy(onOpenPlans = { opened = true }),
        ) } }
        val resume = rule.onNodeWithText("Resume").fetchSemanticsNode().boundsInRoot
        val nothing = rule.onNodeWithText("Nothing on Today").fetchSemanticsNode().boundsInRoot
        assertTrue(resume.top < nothing.top)
        rule.onNodeWithText("Up next").assertDoesNotExist()
        rule.onNodeWithText("Plans").performClick()
        assertTrue(opened)
    }

    @Test fun factFormatsTheLastTrainedStringAroundTheDate() {
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, upNext = listOf(first), fact = "Oct 28"), actions(),
        ) } }
        rule.onNodeWithText("Last trained Oct 28").assertExists()
    }

    @Test fun otherDaysExpandAndRouteStrengthToPreviewAndCardioToDay() {
        val state = mutableStateOf(TodayUiState(hasProgram = true, upNext = listOf(first), otherDays = listOf(
            second, TodayDay(1, "Strength", "C1", 0, "Run", cardioOnly = true),
        )))
        val previews = mutableListOf<DaySelection>()
        val cardio = mutableListOf<DaySelection>()
        rule.setContent { AppTheme { TodayScreen(state.value, actions().copy(
            onToggleOtherDays = { state.value = state.value.copy(otherDaysExpanded = !state.value.otherDaysExpanded) },
            onOpenPreview = { previews += it }, onOpenCardio = { cardio += it },
        )) } }
        rule.onNodeWithText("Prep · Day A").assertDoesNotExist()
        rule.onNodeWithText("Other days").performClick()
        rule.onNodeWithText("Prep · Day A").performClick()
        rule.onNodeWithText("Strength · Day C1").performClick()
        assertEquals(listOf(second.selection), previews)
        assertEquals(listOf(DaySelection(1, "C1")), cardio)
    }

    @Test fun conflictNamesOpenWorkoutAndConfirmsBeforeDiscarding() {
        val run = OpenRun("run", 2, "B", "Upper", "Prep", 1000, emptyList())
        val conflict = mutableStateOf(WorkoutConflict(run, false))
        var discarded = false
        var resumed = false
        rule.setContent { AppTheme { WorkoutConflictDialog(
            conflict.value, {}, { resumed = true },
            { conflict.value = conflict.value.copy(confirming = true) }, { discarded = true },
        ) } }
        rule.onNodeWithText("Prep · Day B Upper").assertExists()
        rule.onNodeWithText("Resume").performClick()
        assertTrue(resumed)
        rule.onNodeWithText("Discard and start").performClick()
        rule.onNodeWithText("Discard workout?").assertExists()
        assertEquals(false, discarded)
        rule.onNodeWithText("Discard and start").performClick()
        assertTrue(discarded)
    }
}
