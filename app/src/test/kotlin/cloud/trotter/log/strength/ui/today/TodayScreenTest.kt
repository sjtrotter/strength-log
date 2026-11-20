package cloud.trotter.log.strength.ui.today

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import cloud.trotter.log.strength.data.OpenRun
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.day.WorkoutConflict
import cloud.trotter.log.strength.ui.day.WorkoutConflictDialog
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TodayScreenTest {
    @Test fun `a run day names its activity`() {
        val day = first.copy(title = "Easy", cardioOnly = true,
            cardioActivity = cloud.trotter.log.strength.domain.model.CardioActivity.ROAD_RUN)
        val state = mutableStateOf(TodayUiState(hasProgram = true, upNext = listOf(day)))
        rule.setContent { AppTheme { TodayScreen(state.value, actions()) } }
        rule.onNodeWithText("Easy, road run").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(upNext = listOf(day.copy(liftCount = 2))) }
        rule.onNodeWithText("Easy, road run and 2 lifts").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(upNext = listOf(day.copy(liftCount = 1))) }
        rule.onNodeWithText("Easy, road run and 1 lift").assertIsDisplayed()
    }

    @Test fun lighterTodayStartsTheSelectedDayAndHidesForResumeRestAndCardio() {
        val next = first.copy(lighterAvailable = true)
        val state = mutableStateOf(TodayUiState(hasProgram = true, upNext = listOf(next)))
        var started: DaySelection? = null
        rule.setContent { AppTheme { TodayScreen(state.value, actions().copy(onStartLighter = { started = it })) } }
        rule.onNodeWithText("Lighter today").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(next.selection, started); state.value = state.value.copy(resume = next, resumeDraft = true) }
        rule.onNodeWithText("Lighter today").assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(resume = null, upNext = listOf(next.copy(rest = true))) }
        rule.onNodeWithText("Lighter today").assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(upNext = listOf(next.copy(cardioOnly = true))) }
        rule.onNodeWithText("Lighter today").assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(upNext = listOf(next.copy(lighterAvailable = false))) }
        rule.onNodeWithText("Lighter today").assertDoesNotExist()
    }

    @get:Rule val rule = createComposeRule()
    private val first = todayFirstFixture()
    private val second = todaySecondFixture()
    private fun actions() = TodayActions(onStart = {}, onOpenSettings = {}, onSetUpProgram = {})

    @Test fun doneForTodayShowsItsReasonFactAndPlansAction() {
        var opened = false
        rule.setContent { AppTheme {
            TodayScreen(TodayUiState(nothingOnToday = true, fact = "Oct 28"),
                actions().copy(onOpenPlans = { opened = true }))
        } }
        rule.onNodeWithText("Done for today").assertIsDisplayed()
        rule.onNodeWithText("Your next workout shows here tomorrow.").assertIsDisplayed()
        rule.onNodeWithText("Last trained Oct 28").assertIsDisplayed()
        rule.onNodeWithText("Your plans are paused").assertDoesNotExist()
        rule.onNodeWithText("Open plans").performClick()
        assertTrue(opened)
    }

    @Test fun pausedPlansShowTheirReasonAndOpenPlans() {
        var opened = false
        rule.setContent { AppTheme {
            TodayScreen(TodayUiState(nothingOnToday = true, plansPaused = true),
                actions().copy(onOpenPlans = { opened = true }))
        } }
        rule.onNodeWithText("Your plans are paused").assertIsDisplayed()
        rule.onNodeWithText("Resume one from Plans.").assertIsDisplayed()
        rule.onNodeWithText("Done for today").assertDoesNotExist()
        rule.onNodeWithText("Open plans").performClick()
        assertTrue(opened)
    }

    @Test fun readyStateHasOneStartDayAOutsideTheListAndRowsOpenPreview() {
        val dayA = first.copy(dayId = "A", dayIndex = 0)
        val dayB = second.copy(dayId = "B", dayIndex = 1)
        val previews = mutableListOf<DaySelection>()
        val starts = mutableListOf<DaySelection>()
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, upNext = listOf(dayA, dayB)),
            actions().copy(onOpenPreview = { previews += it }, onStart = { starts += it }),
        ) } }
        val firstRow = rule.onNodeWithText("Strength · Day A").fetchSemanticsNode().boundsInRoot
        val secondRow = rule.onNodeWithText("Prep · Day B").fetchSemanticsNode().boundsInRoot
        assertTrue(firstRow.top < secondRow.top)
        rule.onAllNodes(hasText("Start day A")).assertCountEquals(1)
        rule.onNode(hasText("Start day A") and hasAnyAncestor(hasScrollAction())).assertDoesNotExist()
        rule.onNodeWithText("Start").assertDoesNotExist()
        rule.onNodeWithText("Prep · Day B").performClick()
        rule.onNodeWithText("Start day A").assertIsDisplayed().performClick()
        assertEquals(listOf(dayB.selection), previews)
        assertEquals(listOf(dayA.selection), starts)
    }

    @Test fun resumeDayAUsesTheBottomSlotAndTheNextDayStillStarts() {
        var resumed: DaySelection? = null
        var started: DaySelection? = null
        val previews = mutableListOf<DaySelection>()
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, resume = second, upNext = listOf(first)),
            actions().copy(onResume = { resumed = it }, onStart = { started = it }, onOpenPreview = { previews += it }),
        ) } }
        rule.onAllNodes(hasText("Resume day A")).assertCountEquals(1)
        rule.onNode(hasText("Resume day A") and hasAnyAncestor(hasScrollAction())).assertDoesNotExist()
        rule.onNodeWithText("Prep · Day A").performClick()
        rule.onNodeWithText("Strength · Day B").performClick()
        rule.onNodeWithText("Resume day A").assertIsDisplayed().performClick()
        rule.onNodeWithText("Start").performClick()
        assertEquals(listOf(second.selection, first.selection), previews)
        assertEquals(second.selection, resumed)
        assertEquals(first.selection, started)
    }

    @Test fun pausedOpenRunKeepsResumeAndPlansWithoutAnUpNextHeader() {
        var opened = false
        var resumed: DaySelection? = null
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, nothingOnToday = true, plansPaused = true, resume = second),
            actions().copy(onOpenPlans = { opened = true }, onResume = { resumed = it }),
        ) } }
        rule.onNodeWithText("Resume day A").assertIsDisplayed().performClick()
        rule.onNodeWithText("Your plans are paused").assertIsDisplayed()
        rule.onNodeWithText("Up next").assertDoesNotExist()
        rule.onNodeWithText("Open plans").performClick()
        assertEquals(second.selection, resumed)
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
    @Test fun restRowSkipsToTrainingAndUsesTheBottomRestAction() {
        val rest = first.copy(dayId = "C", dayIndex = 2, title = "Rest", rest = true, trainDayId = "A")
        var taken: DaySelection? = null
        var skipped: DaySelection? = null
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, upNext = listOf(rest)),
            actions().copy(onRestTaken = { taken = it }, onSkipRest = { skipped = it }),
        ) } }
        rule.onNodeWithText("C — Rest").assertIsDisplayed().assertHasNoClickAction()
        rule.onNodeWithText("Skip rest and train").performClick()
        assertEquals(DaySelection(rest.planId, "A"), skipped)
        rule.onNode(hasText("Rest taken") and hasAnyAncestor(hasScrollAction())).assertDoesNotExist()
        rule.onNodeWithText("Rest taken").assertIsDisplayed().performClick()
        assertEquals(rest.selection, taken)
    }

    @Test fun anOpenWorkoutKeepsResumeAndRestTakenInTheRow() {
        val rest = first.copy(dayId = "C", dayIndex = 2, title = "Rest", rest = true, trainDayId = "A")
        var taken: DaySelection? = null
        var resumed: DaySelection? = null
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, resume = second, upNext = listOf(rest)),
            actions().copy(onRestTaken = { taken = it }, onResume = { resumed = it }),
        ) } }
        rule.onNode(hasText("Rest taken") and hasAnyAncestor(hasScrollAction())).assertIsDisplayed().performClick()
        assertEquals(rest.selection, taken)
        rule.onNodeWithText("Resume day A").performClick()
        assertEquals(second.selection, resumed)
    }

    @Test fun aSecondaryRestRowHasItsOwnRestAction() {
        val rest = second.copy(dayId = "C", dayIndex = 2, title = "Rest", rest = true, trainDayId = "A")
        var taken: DaySelection? = null
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, upNext = listOf(first, rest)),
            actions().copy(onRestTaken = { taken = it }),
        ) } }
        rule.onNodeWithText("Start day B").assertIsDisplayed()
        rule.onNode(hasText("Rest taken") and hasAnyAncestor(hasScrollAction())).performClick()
        assertEquals(rest.selection, taken)
    }

    @Test fun `aDraftResumeSaysStartAndStarts`() {
        val starts = mutableListOf<DaySelection>()
        val resumes = mutableListOf<DaySelection>()
        rule.setContent { AppTheme { TodayScreen(
            TodayUiState(hasProgram = true, resume = second, resumeDraft = true, upNext = listOf(first)),
            actions().copy(onStart = { starts += it }, onResume = { resumes += it }),
        ) } }
        rule.onNodeWithText("Resume day A").assertDoesNotExist()
        rule.onNodeWithText("Start day A").assertIsDisplayed().performClick()
        assertEquals(listOf(second.selection), starts)
        assertTrue(resumes.isEmpty())
    }

    @Test fun `a rest row before a sprint day says so`() {
        val state = mutableStateOf(TodayUiState(hasProgram = true,
            upNext = listOf(first.copy(rest = true, beforeSprint = true))))
        rule.setContent { AppTheme { TodayScreen(state.value, actions()) } }
        rule.onNodeWithText("Rest before your next sprint day.").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(upNext = listOf(first.copy(rest = true))) }
        rule.onNodeWithText("Rest before your next sprint day.").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun restBeforeSprintKeepsWholeWordsAtFontScaleTwo() {
        val rest = TodayDay(1, "Sprint", "B", 1, "Rest", rest = true, trainDayId = "C", beforeSprint = true)
        var skipped: DaySelection? = null
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme { TodayScreen(TodayUiState(hasProgram = true, upNext = listOf(rest)),
                    actions().copy(onSkipRest = { skipped = it })) }
            }
        }
        val note = "Rest before your next sprint day."
        val node = rule.onNodeWithText(note, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertEquals(2f, layout.layoutInput.density.fontScale, 0f)
        assertFalse("Rest note exceeds its height", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse("Rest note is ellipsized on line $line", layout.isLineEllipsized(line))
            val end = layout.getLineEnd(line, visibleEnd = true)
            assertTrue("Rest note splits a word on line $line",
                end == note.length || note[end].isWhitespace() || end > 0 && note[end - 1].isWhitespace())
            assertTrue("Rest note exceeds its width on line $line",
                bounds.width + 1f >= layout.getLineRight(line) - layout.getLineLeft(line))
        }
        val skip = rule.onNodeWithText("Skip rest and train").assertIsDisplayed()
        assertTrue(skip.fetchSemanticsNode().boundsInRoot.top >= bounds.bottom)
        skip.performClick()
        assertEquals(DaySelection(rest.planId, "C"), skipped)
    }

}
