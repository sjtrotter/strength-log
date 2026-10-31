package cloud.trotter.log.strength.ui.day

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun generatedHeaderKeepsItsExactGoalAndLastText() {
        val card = pagerFixture().exercises[1].copy(
            headerTarget = HeaderTarget.Goal("235"), lastTimeDisplay = "185 × 8",
        )
        show(pagerFixture().copy(exercises = listOf(card)))
        rule.onNodeWithText("Goal 235").assertIsDisplayed()
        rule.onNodeWithText("Last 185 × 8").assertIsDisplayed()

        // The title and the unmodified target Column remain direct children of the original Row.
        val title = rule.onNodeWithText(card.title, useUnmergedTree = true).fetchSemanticsNode().layoutInfo
        val goal = rule.onNodeWithText("Goal 235", useUnmergedTree = true).fetchSemanticsNode().layoutInfo
        val last = rule.onNodeWithText("Last 185 × 8", useUnmergedTree = true).fetchSemanticsNode().layoutInfo
        val targetColumn = requireNotNull(goal.parentInfo)
        val headerRow = requireNotNull(title.parentInfo)
        assertSame(targetColumn, last.parentInfo)
        assertSame(headerRow, targetColumn.parentInfo)
        assertTrue("Generated target Column must keep Modifier", targetColumn.getModifierInfo().isEmpty())
        assertTrue("Generated header Row must keep Modifier", headerRow.getModifierInfo().isEmpty())
        val layout = textLayout("Last 185 × 8")
        assertEquals(Int.MAX_VALUE, layout.layoutInput.maxLines)
        assertEquals(TextOverflow.Clip, layout.layoutInput.overflow)
    }

    @Test
    @Config(qualifiers = "w320dp-h891dp")
    fun longPrescribedBandKeepsTitleAndMenuAtEnlargedFontScale() {
        val band = "A".repeat(40)
        val supporting = "Last 3 × 12 · Each side · $band"
        val card = pagerFixture().exercises[1].copy(
            title = "Split squat",
            headerTarget = HeaderTarget.Prescribed("3 × 12", eachSide = true, resistance = band),
            lastTimeDisplay = "3 × 12",
        )
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) {
                AppTheme {
                    DayScreen(pagerFixture().copy(exercises = listOf(card)), pagerActions(),
                        DayEditUiState(), pagerEditActions())
                }
            }
        }

        val title = rule.onNodeWithText("Split squat").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val menu = rule.onNodeWithContentDescription("Split squat menu")
            .assertIsDisplayed().assertWidthIsEqualTo(48.dp)
        assertTrue("Title must fit before the menu", title.right <= menu.fetchSemanticsNode().boundsInRoot.left)
        assertTrue("Title must remain fully readable", !textLayout("Split squat").hasVisualOverflow)
        rule.onNodeWithContentDescription("Target 3 × 12").assertIsDisplayed()
        // Text semantics retain the entire string even though only one ellipsized line is drawn.
        rule.onNodeWithText(supporting).assertIsDisplayed()
        val layout = textLayout(supporting)
        assertEquals(supporting, layout.layoutInput.text.text)
        assertEquals(1, layout.layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
        assertEquals(1, layout.lineCount)
        // No isLineEllipsized check: Robolectric's text measurement does not
        // produce real glyph widths, so it cannot observe the ellipsis itself.
        menu.performClick()
        rule.onNodeWithText("Note").assertIsDisplayed()
    }

    @Test fun prescribedHeadersShowEveryTargetInBothUnitsAndReadTheVisibleLabel() {
        val cases = listOf(
            Triple(PrescribedTarget.LoadReps(95.0, 10), "Target 3 × 10 · 95 lb", "Target 3 × 10 · 43.09 kg"),
            Triple(PrescribedTarget.Reps(12), "Target 3 × 12", "Target 3 × 12"),
            Triple(PrescribedTarget.Hold(45), "Target 3 × 0:45", "Target 3 × 0:45"),
            Triple(PrescribedTarget.Hold(45, 25.0), "Target 3 × 0:45 · 25 lb", "Target 3 × 0:45 · 11.34 kg"),
            Triple(PrescribedTarget.RepsInTime(120), "Target 2:00", "Target 2:00"),
            Triple(PrescribedTarget.RepsInTime(120, 40), "Target 2:00 · aim 40", "Target 2:00 · aim 40"),
        )
        val base = pagerFixture().exercises[1].copy(lastTimeDisplay = "3 × 12")
        var card by mutableStateOf(base)
        rule.setContent { AppTheme {
            DayScreen(pagerFixture().copy(exercises = listOf(card)), pagerActions(), DayEditUiState(), pagerEditActions())
        } }
        for ((target, lb, kg) in cases) {
            for ((unit, label) in listOf(WeightUnit.LB to lb, WeightUnit.KG to kg)) {
                rule.runOnIdle {
                    card = base.copy(headerTarget = DayScreenBuilder.headerTarget(
                        ProgramExercise("weighted_plank", targetSets = 3,
                            prescription = Prescription(target, eachSide = true, resistance = "Red band")),
                        GoalTarget.Weight(235.0, perHand = false), unit,
                    ))
                }
                rule.onNodeWithText(label).assertIsDisplayed()
                rule.onNodeWithContentDescription(label).assertIsDisplayed()
                rule.onNodeWithText("Last 3 × 12 · Each side · Red band").assertIsDisplayed()
            }
        }
    }

    @Test fun prescribedDetailsAppearWithoutHistory() {
        val card = pagerFixture().exercises[1].copy(
            headerTarget = HeaderTarget.Prescribed("3 × 12", eachSide = true, resistance = "Red band"),
        )
        show(pagerFixture().copy(exercises = listOf(card)))
        rule.onNodeWithText("Each side · Red band").assertIsDisplayed()
    }

    @Test fun blankResistanceAddsNoSupportingLine() {
        val card = pagerFixture().exercises[1].copy(
            headerTarget = HeaderTarget.Prescribed("3 × 12", resistance = "  "),
        )
        show(pagerFixture().copy(exercises = listOf(card)))
        rule.onNodeWithText("Target 3 × 12").assertIsDisplayed()
        rule.onNodeWithText("Each side", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Last", substring = true).assertDoesNotExist()
        rule.onNodeWithText("  ").assertDoesNotExist()
    }

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
            pagerFixture().copy(isProgramDay = false, cardio = cardioFixture()),
            pagerActions(), DayEditUiState(), pagerEditActions(), standaloneCardio = true,
        ) } }
        rule.onNodeWithText("Lift 1 of 1").assertExists()
        rule.onNodeWithContentDescription("Road run, not started").assertExists()
        rule.onNodeWithText("Finish day D").assertDoesNotExist()
    }

    @Test fun resolvedNullSheetReenablesDayBack() {
        var state by mutableStateOf(pagerFixture().copy(openSheet = OpenSheet("D", OpenSheet.Kind.EDITOR, 2L, 2)))
        var backDispatcher: OnBackPressedDispatcher? = null
        var exits = 0
        rule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            AppTheme {
                DayScreen(state, pagerActions(), DayEditUiState(), pagerEditActions(), onBack = { exits++ })
            }
        }
        rule.onNodeWithText("Weight, lb").assertExists()
        rule.runOnIdle {
            // The ViewModel resolves a removed edited row to null; the screen receives only that result.
            state = state.copy(openSheet = null, exercises = state.exercises.map { card ->
                if (card.programExerciseId == 2L) card.copy(rows = card.rows.dropLast(1)) else card
            })
        }
        rule.waitForIdle()
        rule.onNodeWithText("Weight, lb").assertDoesNotExist()
        rule.runOnUiThread { backDispatcher!!.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, exits)
    }

    @Test fun initialSettleReportsPageZeroKeyWhenUnseeded() {
        val unseeded = pagerFixture().let { state ->
            state.copy(exercises = state.exercises.map { it.copy(rows = emptyList(), allDone = false, focusIndex = -1) })
        }
        val selected = mutableListOf<String>()
        show(unseeded, pagerActions().copy(onSelectPage = { selected += it }))
        rule.waitForIdle()
        rule.onNodeWithText("Lift 1 of 3").assertIsDisplayed()
        assertEquals(listOf("exercise:1"), selected)
    }

    @Test fun completingPinnedFirstLiftLeavesPagerOnIt() {
        var state by mutableStateOf(pagerFixture().let { base ->
            base.copy(
                selectedPageKey = "exercise:1",
                exercises = base.exercises.map { card ->
                    if (card.programExerciseId == 1L) card.copy(
                        rows = listOf(card.rows.first().copy(done = false)), allDone = false, focusIndex = 0,
                    ) else card
                },
            )
        })
        val selected = mutableListOf<String>()
        val actions = pagerActions().copy(
            onSelectPage = { key ->
                selected += key
                state = state.copy(selectedPageKey = key)
            },
            onToggleDone = { id, index, checked, _ ->
                state = state.copy(exercises = state.exercises.map { card ->
                    if (card.programExerciseId == id) card.copy(
                        rows = card.rows.map { if (it.index == index) it.copy(done = checked) else it },
                        allDone = true, focusIndex = -1,
                    ) else card
                })
            },
        )
        rule.setContent { AppTheme { DayScreen(state, actions, DayEditUiState(), pagerEditActions()) } }
        // The peeking neighbour page is composed too and may carry the same label.
        rule.onNode(hasText("Log 85 × 5") and hasAnyAncestor(hasTestTag("exercise-page:1"))).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Lift 1 of 3").assertIsDisplayed()
        assertEquals(0, state.currentPage)
        assertEquals(listOf("exercise:1"), selected)
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun show(state: DayUiState = pagerFixture(), actions: DayActions = pagerActions()) {
        var current by mutableStateOf(state)
        val wiredActions = actions.copy(
            onOpenSetEditor = { id, index -> current = current.copy(openSheet = OpenSheet("D", OpenSheet.Kind.EDITOR, id, index)) },
            onCloseSetEditor = { current = current.copy(openSheet = null) },
            onOpenAllSets = { id -> current = current.copy(openSheet = OpenSheet("D", OpenSheet.Kind.ALL_SETS, id, 0)) },
            onCloseAllSets = { current = current.copy(openSheet = null) },
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
            isMain = true, isSuperset = false, hasWarmupHint = false, headerTarget = HeaderTarget.Goal("120"), perHand = false,
            allDone = page == 0, rows = rows, focusIndex = focus,
            focusLabel = UiText.SetLabel(SetKind.RAMP, focus + 1), summaryLine = "85×5, 95×5, 105×5")
    },
)

internal fun pagerActions() = DayActions(
    onSelectDay = {}, onWeightChange = { _, _, _, _ -> }, onRepsChange = { _, _, _, _ -> },
    onSecondsChange = { _, _, _, _ -> }, onToggleDone = { _, _, _, _ -> }, onAddSet = { _, _ -> },
    onRemoveSet = { _, _, _ -> }, onSelectPage = {}, onKeepScreenOnChange = {}, onDiscardWorkout = {},
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
