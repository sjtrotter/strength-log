package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TopAppBar has no scrollBehavior by construction in DayScreen; cards scroll internally in short windows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class WorkoutPagerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun opensOnFirstUnfinishedLiftAndLogsItsFocusedRound() {
        val logged = mutableListOf<Pair<Long, Int>>()
        show(actions = pagerActions().copy(onToggleDone = { id, index, checked, superset ->
            assertEquals(true, checked)
            assertEquals(false, superset)
            logged += id to index
        }))
        rule.onNodeWithText("Lift 2 of 3").assertIsDisplayed()
        rule.onNodeWithText("Log 95 × 5").performClick()
        assertEquals(listOf(2L to 1), logged)
    }

    @Test fun dayAndSetSegmentsDescribeProgressWithoutClickActions() {
        show()
        rule.onNodeWithContentDescription("Lift 1, done").assert(hasClickAction().not())
        rule.onNodeWithContentDescription("Lift 2, 1 of 3 sets").assert(hasClickAction().not())
        rule.onNodeWithContentDescription("Lift 3, not started").assert(hasClickAction().not())
        rule.onNode(
            hasContentDescription("Ramp set 1, done") and hasAnyAncestor(hasTestTag("exercise-page:2")),
        ).assert(hasClickAction().not())
        rule.onNode(
            hasContentDescription("Ramp set 2, not started") and hasAnyAncestor(hasTestTag("exercise-page:2")),
        ).assert(hasClickAction().not())
    }

    @Test fun exercisesSheetNavigatesWithoutLogging() {
        val selected = mutableListOf<String>()
        var logged = 0
        show(actions = pagerActions().copy(
            onSelectPage = { selected += it },
            onToggleDone = { _, _, _, _ -> logged++ },
        ))
        rule.onNodeWithText("Lift 2 of 3").performClick()
        rule.onNode(hasText("Lift 3") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals("exercise:3", selected.last())
        assertEquals(0, logged)
    }

    @Test fun restMenuAdjustsBothWaysAndSkipEndsRest() {
        val adjustments = mutableListOf<Int>()
        var skipped = 0
        show(
            state = pagerFixture().copy(rest = pagerRest()),
            actions = pagerActions().copy(onAdjustRest = { adjustments += it }, onSkipRest = { skipped++ }),
        )
        rule.onNodeWithContentDescription("Rest, 1:30, adjust").performClick()
        rule.onNodeWithText("15 s less").performClick()
        rule.onNodeWithContentDescription("Rest, 1:30, adjust").performClick()
        rule.onNodeWithText("15 s more").performClick()
        rule.onNodeWithText("Skip").performClick()
        assertEquals(listOf(-15, 15), adjustments)
        assertEquals(1, skipped)
    }

    @Test fun expiredRestShowsRestOver() {
        show(state = pagerFixture().copy(rest = pagerRest().copy(remainingSeconds = 0, over = true)))
        rule.onNodeWithText("Rest over").assertIsDisplayed()
        rule.onNodeWithContentDescription("Rest, Rest over, adjust").assertExists()
    }

    @Test fun focusNumeralOpensTheSetEditSheet() {
        show()
        rule.onNodeWithText("95 × 5").performClick()
        rule.onNodeWithText("Weight, lb").assertExists()
        rule.onAllNodesWithText("Ramp set 2").onFirst().assertExists()
        rule.onNodeWithText("Done").assertExists()
    }

    @Test fun allSetsOpensThreeRoundCheckboxes() {
        show()
        rule.onNode(hasText("All sets") and hasAnyAncestor(hasTestTag("exercise-page:2"))).performClick()
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertCountEquals(3)
        rule.onNodeWithText("Add a set").assertExists()
    }

    @Test fun timedPageKeepsSecondsAndOptionalWeight() {
        val state = pagerFixture().let { base -> base.copy(exercises = listOf(base.exercises[1].copy(
            tracking = TrackingType.TIMED, timedShowsWeight = true,
            rows = listOf(SetRowState(0, "1", false, 25.0, 0, false, seconds = 45)),
            focusIndex = 0, focusLabel = UiText.SetLabel(SetKind.WORK, 1),
        ))) }
        show(state)
        rule.onNodeWithText("45 s at 25").assertExists()
        rule.onNodeWithText("Log 45 s at 25").assertExists()
    }

    @Test fun repPageShowsRepsWithoutInventingWeight() {
        val card = pagerFixture().exercises[1].copy(tracking = TrackingType.REPS,
            rows = listOf(SetRowState(0, "1", false, 0.0, 6, false)), focusIndex = 0)
        show(pagerFixture().copy(exercises = listOf(card)))
        rule.onNodeWithText("6 reps").assertExists()
        rule.onNodeWithText("Log 6 reps").assertExists()
    }

    @Test fun standaloneCardioHasOnePageAndNoFinishBar() {
        rule.setContent { AppTheme { DayScreen(
            pagerFixture().copy(isProgramDay = false, cardio = CardioCardState("Run", "20 minutes", false)),
            pagerActions(), DayEditUiState(), pagerEditActions(), standaloneCardio = true,
        ) } }
        rule.onNodeWithText("Lift 1 of 1").assertExists()
        rule.onNodeWithContentDescription("Run, not started").assertExists()
        rule.onNodeWithText("Finish day D").assertDoesNotExist()
    }

    private fun show(state: DayUiState = pagerFixture(), actions: DayActions = pagerActions()) {
        var current by mutableStateOf(state)
        val wiredActions = actions.copy(
            onOpenSetEditor = { id, index -> current = current.copy(editSetId = id, editSetIndex = index, allSetsId = null) },
            onCloseSetEditor = { current = current.copy(editSetId = null) },
            onOpenAllSets = { id -> current = current.copy(allSetsId = id, editSetId = null) },
            onCloseAllSets = { current = current.copy(allSetsId = null) },
        )
        rule.setContent { AppTheme { DayScreen(current, wiredActions, DayEditUiState(), pagerEditActions()) } }
    }
}

internal fun pagerFixture(): DayUiState = DayUiState(
    hasProgram = true, viewDayId = "D", dayIndex = 3, dayTitle = "Full body",
    exercises = List(3) { page ->
        val rows = List(3) { index -> SetRowState(index, "R${index + 1}", false, 85.0 + index * 10, 5,
            done = page == 0 || page == 1 && index == 0, kind = SetKind.RAMP, rampIndex = index + 1) }
        val focus = DayScreenBuilder.focusIndex(rows)
        ExerciseCardState(programExerciseId = page + 1L, position = page, title = "Lift ${page + 1}",
            isMain = true, isSuperset = false, hasWarmupHint = false, goalDisplay = "120", perHand = false,
            allDone = page == 0, rows = rows, focusIndex = focus,
            focusLabel = UiText.SetLabel(SetKind.RAMP, focus + 1), summaryLine = "85×5, 95×5, 105×5")
    },
)

internal fun pagerActions() = DayActions(
    onSelectDay = {}, onWeightChange = { _, _, _, _ -> }, onRepsChange = { _, _, _, _ -> },
    onSecondsChange = { _, _, _, _ -> }, onToggleDone = { _, _, _, _ -> }, onAddSet = { _, _ -> },
    onRemoveSet = { _, _, _ -> }, onSelectPage = {}, onKeepScreenOnChange = {}, onClearChecks = {},
    onDone = {}, onCreateExercise = {}, onSetUpProgram = {},
)

internal fun pagerEditActions() = DayEditActions(onSwap = { _, _ -> }, onAdd = {}, onRemove = {},
    onSetSuperset = { _, _ -> }, onRemoveSuperset = {}, onResetToTemplate = {})

internal fun pagerRest() = RestUiState(
    rest = PhoneRest(deadlineEpochMillis = 90_000L, totalSeconds = 90, nextSetLabel = "Ramp set 2"),
    remainingSeconds = 90,
    remainingFraction = 1f,
    over = false,
)
