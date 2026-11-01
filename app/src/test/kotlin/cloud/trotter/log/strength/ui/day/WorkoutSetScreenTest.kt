package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
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
class WorkoutSetScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun singleLiftShowsSetAndPlatesAndGuardsDoubleTaps() {
        var logs = 0
        show(workoutSetSingleFixture(), pagerActions().copy(onLogFocusedSet = { logs++ }))
        rule.onNodeWithText("Barbell Back Squat").assertIsDisplayed()
        rule.onNodeWithText("Set 3 of 6 (R3)").assertIsDisplayed()
        rule.onNodeWithText("45 + 25 + 2.5 a side").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        val log = rule.onNodeWithText("Log 190 × 5").assertIsEnabled()
        log.performClick()
        log.performClick()
        rule.runOnIdle { assertEquals(1, logs) }
        rule.mainClock.advanceTimeBy(599, ignoreFrameDuration = true)
        log.performClick()
        rule.runOnIdle { assertEquals(1, logs) }
        rule.mainClock.advanceTimeBy(2, ignoreFrameDuration = true)
        log.performClick()
        rule.runOnIdle { assertEquals(2, logs) }
    }

    @Test fun supersetKeepsBothHalvesAndOpensTheirSets() {
        var state by mutableStateOf(workoutSetSupersetFixture())
        val opened = mutableListOf<Long>()
        val actions = pagerActions().copy(onOpenAllSets = { opened += it })
        rule.setContent { AppTheme { WorkoutSetHost(state, actions, DayEditUiState(), pagerEditActions()) } }
        rule.onNodeWithText("Superset, round 2 of 3").assertIsDisplayed()
        rule.onNode(hasText("Now") and hasTestTag("workout-half-${Slot.MAIN}")).assertExists()
        rule.onNode(hasText("Next") and hasTestTag("workout-half-${Slot.SS}")).assertExists()
        rule.onNode(hasText("120 × 10") and hasTestTag("workout-half-${Slot.SS}")).assertExists()
        for (slot in listOf(Slot.MAIN, Slot.SS)) {
            rule.onNodeWithTag("workout-half-$slot").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        }
        rule.runOnIdle {
            assertEquals(listOf(1L, 1L), opened)
            val card = state.exercises.single()
            state = state.copy(
                exercises = listOf(card.copy(rows = card.rows.map { if (it.index == 1) it.copy(done = true) else it })),
                workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1)),
            )
        }
        rule.onNode(hasText("Done") and hasTestTag("workout-half-${Slot.MAIN}")).assertExists()
        rule.onNode(hasText("80 × 10") and hasTestTag("workout-half-${Slot.MAIN}")).assertExists()
        rule.onNode(hasText("Now") and hasTestTag("workout-half-${Slot.SS}")).assertExists()
        rule.onNodeWithText("Then rest").assertIsDisplayed()
    }

    @Test fun supersetNamesKeepTitleLargeAcrossHalfChanges() {
        var state by mutableStateOf(workoutSetSupersetFixture())
        rule.setContent { AppTheme { WorkoutSetHost(state) } }
        fun nameLayout(name: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(name, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val main = nameLayout("DB Bench Press")
        val partner = nameLayout("Seated Cable Row")
        assertEquals(cloud.trotter.log.strength.ui.theme.AppTypography.titleLarge.fontSize, main.layoutInput.style.fontSize)
        assertEquals(main.layoutInput.style.fontSize, partner.layoutInput.style.fontSize)
        rule.runOnIdle {
            state = state.copy(exercises = state.exercises.map { card -> card.copy(rows = card.rows.map { row ->
                if (row.index == 1) row.copy(done = true) else row
            }) }, workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1)))
        }
        assertEquals(main.size, nameLayout("DB Bench Press").size)
        assertEquals(partner.size, nameLayout("Seated Cable Row").size)
        assertEquals(main.layoutInput.style.fontSize, nameLayout("DB Bench Press").layoutInput.style.fontSize)
        assertEquals(partner.layoutInput.style.fontSize, nameLayout("Seated Cable Row").layoutInput.style.fontSize)
    }

    @Test fun mixedSupersetFormatsThePartnerByItsOwnTracking() {
        val state = workoutSetSupersetFixture()
        val card = state.exercises.single().copy(
            partnerTitle = "Pull-Up", partnerTracking = TrackingType.REPS,
            rows = state.exercises.single().rows.map { it.copy(partner = PartnerRowState(0.0, 8)) },
        )
        show(state.copy(exercises = listOf(card)))
        rule.onNode(hasText("8 reps") and hasTestTag("workout-half-${Slot.SS}")).assertIsDisplayed()
        rule.onNodeWithText("0 × 8").assertDoesNotExist()
    }

    @Test fun swappedPartnerUsesItsOwnPlates() {
        val base = workoutSetSupersetFixture()
        var state by mutableStateOf(base.copy(workout = base.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1))))
        rule.setContent { AppTheme { WorkoutSetHost(state) } }
        rule.onNodeWithText("45 + 25 + 2.5 a side").assertDoesNotExist()
        rule.runOnIdle {
            val card = state.exercises.single().copy(
                partnerTitle = "Barbell Bench Press", partnerExerciseId = "bb_bench", plateLine = "10",
                rows = state.exercises.single().rows.map { row -> row.copy(
                    done = row.index <= 1, partner = row.partner?.copy(weightDisplay = 190.0),
                ) },
            )
            state = state.copy(exercises = listOf(card))
        }
        rule.onNodeWithText("45 + 25 + 2.5 a side").assertIsDisplayed()
        rule.onNodeWithText("10 a side").assertDoesNotExist()
    }

    @Test fun loadedHoldPartnerIncludesItsLoad() {
        val state = workoutSetSupersetFixture()
        val card = state.exercises.single().copy(
            partnerTitle = "Weighted Plank", partnerTracking = TrackingType.TIMED,
            rows = state.exercises.single().rows.map { it.copy(partner = PartnerRowState(25.0, 0, seconds = 45)) },
        )
        show(state.copy(exercises = listOf(card)))
        rule.onNode(hasText("45 s at 25 lb") and hasTestTag("workout-half-${Slot.SS}")).assertIsDisplayed()
    }

    @Test fun missingPartnerRemainsVisibleAsNotAvailable() {
        val state = workoutSetSupersetFixture()
        val card = state.exercises.single().copy(rows = state.exercises.single().rows.map { it.copy(partner = null) })
        show(state.copy(exercises = listOf(card)))
        rule.onNode(hasText("Seated Cable Row") and hasText("Not available") and hasTestTag("workout-half-${Slot.SS}"))
            .assertIsDisplayed().assertHasClickAction()
    }

    @Test fun authoredDetailReplacesPlates() {
        val state = workoutSetSingleFixture()
        val card = state.exercises.single().copy(headerTarget = HeaderTarget.Prescribed("3 × 12", eachSide = true, resistance = "Red band"))
        show(state.copy(exercises = listOf(card)))
        rule.onNodeWithText("Each side, Red band").assertIsDisplayed()
        rule.onNodeWithText("45 + 25 + 2.5 a side").assertDoesNotExist()
    }

    @Test fun liveRestOffersUndoAdjustmentAndSkipAndKeepsLogTonal() {
        var undos = 0
        var adjustment = 0
        var skips = 0
        var logs = 0
        show(workoutSetSingleFixture().copy(rest = pagerRest(), workout = workoutSetSingleFixture().workout?.copy(undo = undo())), pagerActions().copy(
            onUndoLastLog = { undos++ }, onAdjustRest = { adjustment = it }, onSkipRest = { skips++ },
            onLogFocusedSet = { logs++ },
        ))
        rule.onNodeWithText("Rest").assertIsDisplayed()
        rule.onNodeWithText("Undo 165 × 5").performClick()
        rule.onNodeWithText("+15 s").performClick()
        rule.onNodeWithText("Skip rest").performClick()
        rule.onNodeWithTag("workout-action-tonal").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, undos)
            assertEquals(15, adjustment)
            assertEquals(1, skips)
            assertEquals(1, logs)
        }
    }

    @Test fun restOffBandHoldsOnlyUndo() {
        var undos = 0
        show(workoutSetSingleFixture().copy(workout = workoutSetSingleFixture().workout?.copy(undo = undo())), pagerActions().copy(onUndoLastLog = { undos++ }))
        rule.onNodeWithText("Undo 165 × 5").assertIsDisplayed().performClick()
        rule.onNodeWithText("Rest").assertDoesNotExist()
        rule.onNodeWithText("+15 s").assertDoesNotExist()
        rule.onNodeWithText("Skip rest").assertDoesNotExist()
        rule.onAllNodes(hasAnyAncestor(hasTestTag("workout-rest")) and hasText("Undo 165 × 5")).fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
        rule.runOnIdle { assertEquals(1, undos) }
    }

    @Test fun undoForAnotherLiftCarriesItsCatalogShortName() {
        val state = workoutSetSingleFixture()
        val offer = undo().copy(programExerciseId = 9L, set = LoggedSet(175.0, 8, SetKind.BACKOFF))
        show(state.copy(exercises = state.exercises + state.exercises.single().copy(programExerciseId = 9L),
            workout = state.workout?.copy(undo = offer)))
        rule.onNodeWithText("Undo Squat 175 × 8").assertIsDisplayed()
    }

    @Test fun completedViewShowsSummaryAndSelectsNextWithoutLogging() {
        val state = workoutSetSingleFixture()
        val completed = state.exercises.single().copy(
            allDone = true, rows = state.exercises.single().rows.map { it.copy(done = true) },
            summaryLine = "130×5, 165×5, 190×5, 210×3, 235×5, 175×8",
        )
        var nexts = 0
        var logs = 0
        show(state.copy(
            exercises = listOf(completed, completed.copy(programExerciseId = 2L, title = "Barbell Bench Press", allDone = false, rows = completed.rows.map { it.copy(done = false) })),
            workout = state.workout?.copy(shown = WorkoutSelection.Shown.Group("exercise:1", true), focus = null, completedView = true),
        ), pagerActions().copy(onNextWorkoutLift = { nexts++ }, onLogFocusedSet = { logs++ }))
        rule.onNodeWithText("Done, 6 of 6").assertIsDisplayed()
        rule.onNodeWithText(completed.summaryLine).assertIsDisplayed()
        rule.onNodeWithText("Next: Barbell Bench Press").performClick()
        rule.runOnIdle { assertEquals(1, nexts); assertEquals(0, logs) }
    }

    @Test fun allLoggedArmsFinishAtThreeSeconds() {
        rule.mainClock.autoAdvance = false
        val state = workoutSetSingleFixture()
        val completed = state.exercises.single().copy(allDone = true, rows = state.exercises.single().rows.map { it.copy(done = true) })
        var finishes = 0
        rule.setContent { AppTheme {
            WorkoutSetHost(
                state.copy(exercises = listOf(completed), workout = WorkoutState(WorkoutSelection.Shown.AllLogged, null, false, null, 0L)),
                pagerActions().copy(onDone = { finishes++ }), DayEditUiState(), pagerEditActions(),
                nowMillis = { rule.mainClock.currentTime },
            )
        } }
        rule.onNodeWithText("All 6 sets logged").assertIsDisplayed()
        rule.onNodeWithText("Finish").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(2_999L - rule.mainClock.currentTime, ignoreFrameDuration = true)
        rule.onNodeWithText("Finish day A").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(1L, ignoreFrameDuration = true)
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Finish day A").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, finishes) }
    }

    @Test fun loadedHoldShowsSecondsAndLoadAndLogsSeconds() {
        val state = workoutSetSingleFixture()
        val card = state.exercises.single().copy(
            title = "Weighted Plank", tracking = TrackingType.TIMED, timedShowsWeight = true, plateLine = null,
            rows = listOf(SetRowState(0, "1", false, 25.0, 0, false, seconds = 45)),
        )
        show(state.copy(exercises = listOf(card), workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.MAIN, 0))))
        rule.onNodeWithText("45 s").assertIsDisplayed()
        rule.onNodeWithText("at 25 lb").assertIsDisplayed()
        rule.onNodeWithText("Log 45 s").assertIsEnabled()
    }

    @Test fun supersetLogStaysReachableAtLargeFont() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme { WorkoutSetHost(workoutSetSupersetFixture(), pagerActions(), DayEditUiState(), pagerEditActions()) }
            }
        }
        rule.onNodeWithText("Log 80 × 10").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun liftAndHeroUseExistingSheetCallbacks() {
        var sets: Long? = null
        var editor: Pair<Long, Int>? = null
        show(workoutSetSingleFixture(), pagerActions().copy(onOpenAllSets = { sets = it }, onOpenSetEditor = { id, index -> editor = id to index }))
        rule.onNodeWithText("All sets").performClick()
        rule.onNodeWithContentDescription("Edit set 3").performClick()
        rule.runOnIdle { assertEquals(1L, sets); assertEquals(1L to 2, editor) }
    }

    @Test fun partialFinishReusesExistingConfirmation() {
        var finishes = 0
        show(workoutSetSingleFixture(), pagerActions().copy(onDone = { finishes++ }))
        rule.onNodeWithText("Finish").performClick()
        rule.onNodeWithText("Finish with 2 of 6 sets?").assertIsDisplayed()
        rule.onNodeWithText("Keep going").performClick()
        rule.runOnIdle { assertEquals(0, finishes) }
    }

    @Test fun routingKeepsUnsupportedStatesOnThePager() {
        val state = workoutSetSingleFixture()
        assertTrue(state.usesWorkoutSetScreen)
        assertFalse(state.copy(workout = null).usesWorkoutSetScreen)
        assertFalse(state.copy(loading = true).usesWorkoutSetScreen)
        assertFalse(state.copy(hasProgram = false).usesWorkoutSetScreen)
        assertFalse(state.copy(workout = state.workout?.copy(shown = WorkoutSelection.Shown.Empty)).usesWorkoutSetScreen)
        assertFalse(state.copy(workout = state.workout?.copy(shown = WorkoutSelection.Shown.Group("cardio", false))).usesWorkoutSetScreen)
        assertFalse(state.copy(cardioRunning = true).usesWorkoutSetScreen)
        assertFalse(state.copy(exercises = state.exercises.map { it.copy(timedRepDurationSeconds = 40) }).usesWorkoutSetScreen)
        assertTrue(state.copy(workout = state.workout?.copy(shown = WorkoutSelection.Shown.AllLogged)).usesWorkoutSetScreen)
    }

    @Test fun finishConfirmationSurvivesFallbackToPager() {
        var state by mutableStateOf(workoutSetSingleFixture())
        rule.setContent { AppTheme { WorkoutSetHost(state) } }
        rule.onNodeWithText("Finish").performClick()
        rule.onNodeWithText("Finish with 2 of 6 sets?").assertIsDisplayed()
        rule.runOnIdle {
            state = state.copy(exercises = state.exercises.map { it.copy(timedRepDurationSeconds = 40) })
        }
        rule.onNodeWithTag("workout-action-filled").assertDoesNotExist()
        rule.onNodeWithText("Finish with 2 of 6 sets?").assertIsDisplayed()
        rule.onNodeWithText("Keep going").performClick()
    }

    @Test fun editorRemovalShowsSnackbarUndoUnderSetScreen() {
        var state by mutableStateOf(workoutSetSingleFixture())
        var removed by mutableStateOf(emptyList<RemovedSet>())
        var undos = 0
        val actions = pagerActions().copy(
            onOpenSetEditor = { id, index -> state = state.copy(openSheet = OpenSheet("A", OpenSheet.Kind.EDITOR, id, index)) },
            onCloseSetEditor = { state = state.copy(openSheet = null) },
            onRemoveSet = { id, index, _ ->
                removed = listOf(RemovedSet(id, index, "A", "run", LoggedSet(190.0, 5, SetKind.RAMP)))
                state = state.copy(exercises = state.exercises.map { card -> card.copy(rows = card.rows.filter { it.index != index }) })
            },
        )
        rule.setContent { AppTheme { WorkoutSetHost(state, actions, removedSets = removed, onUndoRemoveSet = { undos++ }) } }
        rule.onNodeWithContentDescription("Edit set 3").performClick()
        rule.onNodeWithContentDescription("Set menu").performClick()
        rule.onNodeWithText("Remove set").performClick()
        rule.onNodeWithText("Set removed").assertIsDisplayed()
        rule.onNodeWithText("Undo").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, undos) }
    }

    @Test fun fallbackPagerFollowsWorkoutSelectionAndSwipesSelectWorkoutLift() {
        val base = workoutSetSingleFixture()
        val state = base.copy(selectedPageKey = "exercise:1", cardio = CardioCardState("Cardio", "", false, block = CardioBlock(
            CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(60))),
        )),
            workout = base.workout?.copy(shown = WorkoutSelection.Shown.Group("cardio", false), focus = null))
        val selected = mutableListOf<String>()
        val oldSelected = mutableListOf<String>()
        rule.setContent { AppTheme { WorkoutSetHost(state, pagerActions().copy(
            onSelectWorkoutLift = { selected += it }, onSelectPage = { oldSelected += it },
        )) } }
        rule.onNodeWithText("Lift 2 of 2").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf("cardio"), selected); assertTrue(oldSelected.isEmpty()) }
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("exercise:1", selected.last()); assertTrue(oldSelected.isEmpty()) }
    }

    @Test fun fallbackExercisesSheetSelectsWorkoutLift() {
        val base = workoutSetSingleFixture()
        val selected = mutableListOf<String>()
        val state = base.copy(cardio = CardioCardState("Cardio", "", false, block = CardioBlock(
            CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(60))),
        )),
            workout = base.workout?.copy(shown = WorkoutSelection.Shown.Group("cardio", false), focus = null))
        rule.setContent { AppTheme { WorkoutSetHost(state, pagerActions().copy(onSelectWorkoutLift = { selected += it })) } }
        rule.onNodeWithText("Lift 2 of 2").performClick()
        rule.onNode(hasText("Barbell Back Squat") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("exercise:1", selected.last()) }
    }

    @Test fun duplicateTitlesStillResolvePartnerPlatesAndUndoById() {
        val base = workoutSetSupersetFixture()
        val catalog = ExerciseCatalog(listOf(ExerciseCatalog.CODE_ONLY.get("bb_bench").copy(
            id = "custom_duplicate", shortName = "Wrong", equipment = emptyList(),
        )))
        val card = base.exercises.single().copy(exerciseId = "bb_bench", title = "Barbell Bench Press", partnerTitle = "Barbell Bench Press",
            partnerExerciseId = "bb_bench", rows = base.exercises.single().rows.map {
                it.copy(done = true, partner = it.partner?.copy(weightDisplay = 190.0))
            })
        val offer = undo().copy(slot = Slot.MAIN, title = "Barbell Bench Press")
        rule.setContent { AppTheme { WorkoutSetHost(base.copy(exercises = listOf(card),
            workout = base.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1), undo = offer)),
            dayEditState = DayEditUiState(catalog = catalog)) } }
        rule.onNodeWithText("45 + 25 + 2.5 a side").assertIsDisplayed()
        rule.onNodeWithText("Undo Bench Press 165 × 5").assertIsDisplayed()
    }

    @Test fun restLiveRegionChangesOnlyAtMinuteBoundary() {
        var state by mutableStateOf(workoutSetSingleFixture().copy(rest = pagerRest().copy(remainingSeconds = 62)))
        rule.setContent { AppTheme { WorkoutSetHost(state) } }
        fun announced() = rule.onNodeWithTag("workout-rest").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        rule.onNodeWithTag("workout-rest").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val initial = announced()
        rule.runOnIdle { state = state.copy(rest = state.rest?.copy(remainingSeconds = 61)) }
        assertEquals(initial, announced())
        rule.runOnIdle { state = state.copy(rest = state.rest?.copy(remainingSeconds = 60)) }
        assertEquals(listOf("1 minute of rest remaining"), announced())
        rule.runOnIdle { state = state.copy(rest = state.rest?.copy(remainingSeconds = 59)) }
        assertEquals(listOf("1 minute of rest remaining"), announced())
    }

    @Test
    @Config(qualifiers = "w320dp-h891dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun heroFitsOneLineAtLargeFont() {
        val base = workoutSetSingleFixture()
        val state = base.copy(exercises = base.exercises.map { card -> card.copy(rows = card.rows.map { it.copy(weightDisplay = 1235.0, reps = 12) }) })
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme { WorkoutSetHost(state) }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("workout-hero", useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("1235 × 12", results.single().layoutInput.text.text)
        assertEquals(1, results.single().lineCount)
        assertFalse(results.single().hasVisualOverflow)
    }

    private fun show(state: DayUiState, actions: DayActions = pagerActions()) {
        rule.setContent { AppTheme { WorkoutSetHost(state, actions, DayEditUiState(), pagerEditActions()) } }
    }

    private fun undo() = UndoOffer(1L, Slot.MAIN, 1, "Barbell Back Squat", LoggedSet(165.0, 5, SetKind.RAMP), TrackingType.WEIGHTED, WeightUnit.LB, null)
}
