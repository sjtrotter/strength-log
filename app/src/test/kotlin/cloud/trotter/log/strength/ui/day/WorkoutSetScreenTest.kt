package cloud.trotter.log.strength.ui.day

import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertSame
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
        show(workoutSetSingleFixture(), workoutActions().copy(onLogFocusedSet = { logs++ }))
        rule.onNodeWithText("Barbell Back Squat").assertIsDisplayed()
        rule.onNodeWithText("Set 3 of 6 (R3)").assertIsDisplayed()
        rule.onNodeWithText("45 + 25 + 2.5 a side").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        val log = rule.onNodeWithText("Log 190 × 5").assertIsEnabled()
        log.performClick()
        log.performClick()
        rule.runOnIdle { assertEquals(1, logs) }
        // Each injected click also advances the test clock, so stay clear of the 600 ms edge.
        rule.mainClock.advanceTimeBy(300)
        log.performClick()
        rule.runOnIdle { assertEquals(1, logs) }
        rule.mainClock.advanceTimeBy(700)
        log.performClick()
        rule.runOnIdle { assertEquals(2, logs) }
    }

    @Test fun supersetMainPlacesNameAndHeroAboveNextPartner() {
        show(workoutSetSupersetFixture())
        rule.onNodeWithText("Superset, round 2 of 3").assertIsDisplayed()
        val name = rule.onNodeWithText("DB Bench Press").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val hero = rule.onNodeWithTag("workout-hero", useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("80 × 10").assertIsDisplayed()
        val card = rule.onNodeWithTag("workout-partner-card").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(name.bottom < hero.top)
        assertTrue(hero.bottom < card.top)
        rule.onNodeWithText("Next", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Seated Cable Row", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("120 × 10", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("workout-half-${Slot.SS}")
            .assertContentDescriptionEquals("Seated Cable Row, next, 120 × 10, open sets")
        rule.onNodeWithText("Then rest").assertDoesNotExist()
    }

    @Test fun supersetPartnerAndAllSetsOpenTheirOwnLiftSheets() {
        val opened = mutableListOf<Pair<Long, String>>()
        show(workoutSetSupersetFixture(), workoutActions().copy(onOpenLiftSheet = { id, slot -> opened += id to slot }))
        rule.onNodeWithTag("workout-half-${Slot.SS}")
            .assertHeightIsAtLeast(48.dp).assertHasClickAction().performClick()
        rule.onNodeWithText("All sets").assertHasClickAction().performClick()
        rule.runOnIdle { assertEquals(listOf(1L to Slot.SS, 1L to Slot.MAIN), opened) }
    }

    @Test fun supersetSecondHalfShowsRecordedPartnerAndThenRestOnlyWithTimerOn() {
        val base = workoutSetSupersetFixture()
        val ss = base.copy(exercises = base.exercises.map { card -> card.copy(rows = card.rows.map {
            if (it.index == 1) it.copy(done = true) else it
        }) }, workout = base.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1)))
        var enabled by mutableStateOf(true)
        rule.setContent { AppTheme { WorkoutSetHost(ss.withWorkoutRestHint(enabled, emptyMap())) } }
        rule.onNodeWithText("Seated Cable Row").assertIsDisplayed()
        rule.onNodeWithText("120 × 10").assertIsDisplayed()
        rule.onNodeWithText("Done", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("80 × 10", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("workout-half-${Slot.MAIN}")
            .assertContentDescriptionEquals("DB Bench Press, done, 80 × 10, open sets")
        val card = rule.onNodeWithTag("workout-partner-card").fetchSemanticsNode().boundsInRoot
        val hint = rule.onNodeWithText("Then rest").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(card.bottom < hint.top)
        rule.runOnIdle { enabled = false }
        rule.onNodeWithText("Then rest").assertDoesNotExist()
    }

    @Test fun supersetHalfSwitchFadesInPlaceAndHidesOutgoingTalkBackContent() {
        var state by mutableStateOf(workoutSetSupersetFixture())
        rule.setContent { AppTheme { WorkoutSetHost(state) } }
        val outgoingName = hasTestTag("workout-now-lift") and hasText("DB Bench Press")
        val incomingName = hasTestTag("workout-now-lift") and hasText("Seated Cable Row")
        val before = rule.onNode(outgoingName).fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            state = state.copy(exercises = state.exercises.map { card -> card.copy(rows = card.rows.map {
                if (it.index == 1) it.copy(done = true) else it
            }) }, workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1)))
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val after = rule.onNode(incomingName).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, after.left, 0f)
        assertEquals(before.top, after.top, 0f)
        rule.onNode(outgoingName).assertDoesNotExist()
        rule.onNodeWithTag("workout-half-${Slot.SS}").assertDoesNotExist()
        rule.onNodeWithTag("workout-half-${Slot.MAIN}")
            .assertContentDescriptionEquals("DB Bench Press, done, 80 × 10, open sets")
        rule.onNodeWithTag("workout-superset-content")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.mainClock.advanceTimeBy(150)
        rule.mainClock.advanceTimeByFrame()
        rule.onNode(outgoingName, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("workout-half-${Slot.SS}", useUnmergedTree = true).assertDoesNotExist()
        rule.onNode(incomingName).assertIsDisplayed()
    }

    @Test fun supersetTalkBackTraversesContentThenBandAndLog() {
        val base = workoutSetSupersetFixture()
        show(base.copy(exercises = base.exercises.map { card -> card.copy(
            headerTarget = HeaderTarget.Prescribed("3 × 12", eachSide = true, resistance = "Red band"),
            rows = card.rows.map { row ->
                if (row.index == 1) row.copy(partner = row.partner?.copy(done = true)) else row
            },
        ) }, workout = base.workout?.copy(undo = undo().copy(slot = Slot.SS)))
            .withWorkoutRestHint(true, emptyMap()))

        val content = rule.onNodeWithTag("workout-superset-content").fetchSemanticsNode()
        val actions = rule.onNodeWithTag("workout-superset-actions").fetchSemanticsNode()
        assertTrue(content.config[SemanticsProperties.IsTraversalGroup])
        assertTrue(actions.config[SemanticsProperties.IsTraversalGroup])
        assertTrue(content.config[SemanticsProperties.TraversalIndex] < actions.config[SemanticsProperties.TraversalIndex])
        fun assertOrder(parent: SemanticsNode, vararg nodes: SemanticsNode) {
            fun ids(node: SemanticsNode): List<Int> = listOf(node.id) + node.children.flatMap { ids(it) }
            val expected = nodes.map { it.id }
            assertEquals(expected, ids(parent).filter { it in expected })
        }
        assertOrder(content,
            rule.onNodeWithText("Superset, round 2 of 3").fetchSemanticsNode(),
            rule.onNodeWithTag("workout-now-lift").fetchSemanticsNode(),
            rule.onNodeWithText("All sets").fetchSemanticsNode(),
            rule.onNodeWithContentDescription("Edit set 2").fetchSemanticsNode(),
            rule.onNodeWithText("Each side, Red band").fetchSemanticsNode(),
            rule.onNodeWithTag("workout-half-${Slot.SS}").fetchSemanticsNode(),
            rule.onNodeWithText("Then rest").fetchSemanticsNode(),
        )
        assertOrder(actions,
            rule.onNodeWithTag("workout-rest").fetchSemanticsNode(),
            rule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("workout-rest"))).fetchSemanticsNode(),
            rule.onNodeWithTag("workout-action-filled").fetchSemanticsNode(),
        )
    }

    @Test fun partnerLoggedFirstShowsRecordedValuesAndMainCanCompleteRound() {
        val base = workoutSetSupersetFixture()
        val state = base.copy(exercises = base.exercises.map { card -> card.copy(rows = card.rows.map {
            if (it.index == 1) it.copy(partner = PartnerRowState(115.0, 9, done = true)) else it
        }) }).withWorkoutRestHint(true, emptyMap())
        show(state)
        rule.onNodeWithTag("workout-half-${Slot.SS}")
            .assertContentDescriptionEquals("Seated Cable Row, done, 115 × 9, open sets")
        rule.onNodeWithText("Then rest").assertIsDisplayed()
    }

    @Test fun thenRestRequiresAnotherStrengthSetAndNonzeroRestDuration() {
        val base = workoutSetSupersetFixture()
        val state = base.copy(exercises = base.exercises.map { card -> card.copy(rows = card.rows.map {
            it.copy(done = true, partner = it.partner?.copy(done = it.index != 2))
        }) }, workout = base.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 2)))
        assertFalse(state.withWorkoutRestHint(true, emptyMap()).workoutThenRest)
        val moreRounds = state.copy(exercises = state.exercises.map { card -> card.copy(rows = card.rows.map {
            if (it.index == 0) it.copy(done = false) else it
        }) })
        assertTrue(moreRounds.withWorkoutRestHint(true, emptyMap()).workoutThenRest)
        assertFalse(moreRounds.withWorkoutRestHint(true,
            mapOf(cloud.trotter.log.strength.domain.standards.RestCategory.WORK to 0)).workoutThenRest)
    }

    @Test fun restHintReusesStateWhenUnchanged() {
        val single = workoutSetSingleFixture()
        assertSame(single, single.withWorkoutRestHint(true, emptyMap()))
        val main = workoutSetSupersetFixture()
        assertSame(main, main.withWorkoutRestHint(true, emptyMap()))
        assertSame(main, main.withWorkoutRestHint(false, emptyMap()))
        val noWorkout = main.copy(workout = null)
        assertSame(noWorkout, noWorkout.withWorkoutRestHint(true, emptyMap()))
        val allLogged = main.copy(workout = main.workout?.copy(shown = WorkoutSelection.Shown.AllLogged))
        assertSame(allLogged, allLogged.withWorkoutRestHint(true, emptyMap()))
        val ss = main.copy(exercises = main.exercises.map { card -> card.copy(rows = card.rows.map {
            if (it.index == 1) it.copy(done = true) else it
        }) }, workout = main.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1)))
        assertSame(ss, ss.withWorkoutRestHint(false, emptyMap()))
        val hint = ss.withWorkoutRestHint(true, emptyMap())
        assertTrue(hint.workoutThenRest)
        assertSame(hint, hint.withWorkoutRestHint(true, emptyMap()))
        val cleared = hint.withWorkoutRestHint(false, emptyMap())
        assertFalse(cleared.workoutThenRest)
        assertSame(cleared, cleared.withWorkoutRestHint(false, emptyMap()))
    }

    @Test fun supersetRestShowsNextRoundMainAndNextPartner() {
        val base = workoutSetSupersetFixture()
        show(base.copy(exercises = base.exercises.map { card -> card.copy(rows = card.rows.map {
            if (it.index < 2) it.copy(done = true, partner = it.partner?.copy(done = true)) else it
        }) }, workout = base.workout?.copy(focus = WorkoutFocus(1L, Slot.MAIN, 2)), rest = workoutRest()))
        rule.onNodeWithText("Superset, round 3 of 3").assertIsDisplayed()
        rule.onNodeWithText("DB Bench Press").assertIsDisplayed()
        rule.onNodeWithText("80 × 10").assertIsDisplayed()
        rule.onNodeWithTag("workout-half-${Slot.SS}")
            .assertContentDescriptionEquals("Seated Cable Row, next, 120 × 10, open sets")
        rule.onNodeWithTag("workout-action-tonal").assertIsDisplayed()
        rule.onNodeWithText("Then rest").assertDoesNotExist()
    }

    @Test fun repsOnlyPartnerUsesItsOwnTracking() {
        val state = workoutSetSupersetFixture()
        val card = state.exercises.single().copy(
            partnerTitle = "Pull-Up", partnerTracking = TrackingType.REPS,
            rows = state.exercises.single().rows.map { it.copy(partner = PartnerRowState(0.0, 12)) },
        )
        show(state.copy(exercises = listOf(card)))
        rule.onNodeWithTag("workout-half-${Slot.SS}")
            .assertContentDescriptionEquals("Pull-Up, next, 12 reps, open sets")
    }

    @Test fun timedPartnerUsesItsOwnTracking() {
        val state = workoutSetSupersetFixture()
        val card = state.exercises.single().copy(
            partnerTitle = "Plank", partnerTracking = TrackingType.TIMED,
            rows = state.exercises.single().rows.map { it.copy(partner = PartnerRowState(0.0, 0, seconds = 45)) },
        )
        show(state.copy(exercises = listOf(card)))
        rule.onNodeWithTag("workout-half-${Slot.SS}")
            .assertContentDescriptionEquals("Plank, next, 45 s, open sets")
    }

    @Test fun completedSupersetShowsBothSummariesAndSelectsNext() {
        val base = workoutSetSupersetFixture()
        val completed = base.exercises.single().copy(allDone = true, rows = base.exercises.single().rows.map {
            it.copy(done = true, partner = it.partner?.copy(done = true))
        })
        var next = 0
        show(base.copy(exercises = listOf(completed, workoutSetSingleFixture().exercises.single().copy(
            programExerciseId = 2L, title = "Barbell Row",
        )), workout = base.workout?.copy(shown = WorkoutSelection.Shown.Group("exercise:1", true),
            focus = null, completedView = true)), workoutActions().copy(onNextWorkoutLift = { next++ }))
        rule.onNodeWithText("Done, 3 of 3 rounds").assertIsDisplayed()
        rule.onNodeWithText("DB Bench Press").assertIsDisplayed()
        rule.onNodeWithText("Seated Cable Row").assertIsDisplayed()
        rule.onNodeWithText("80×10, 80×10, 80×10").assertIsDisplayed()
        rule.onNodeWithText("120×10, 120×10, 120×10").assertIsDisplayed()
        rule.onNodeWithTag("workout-hero").assertDoesNotExist()
        rule.onNodeWithTag("workout-partner-card").assertDoesNotExist()
        rule.onNodeWithText("Then rest").assertDoesNotExist()
        rule.onNodeWithText("Next: Barbell Row").performClick()
        rule.runOnIdle { assertEquals(1, next) }
    }

    @Test fun allLoggedSupersetHasNoPartnerCard() {
        val base = workoutSetSupersetFixture()
        show(base.copy(workout = WorkoutState(WorkoutSelection.Shown.AllLogged, null, false, null, 0L)))
        rule.onNodeWithText("All 3 sets logged").assertIsDisplayed()
        rule.onNodeWithTag("workout-partner-card").assertDoesNotExist()
        rule.onNodeWithText("Then rest").assertDoesNotExist()
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
        val hero = rule.onNodeWithTag("workout-hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val plates = rule.onNodeWithText("45 + 25 + 2.5 a side").fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithTag("workout-partner-card").fetchSemanticsNode().boundsInRoot
        assertTrue(hero.bottom < plates.top)
        assertTrue(plates.bottom < card.top)
    }

    @Test fun loadedHoldPartnerIncludesItsLoad() {
        val state = workoutSetSupersetFixture()
        val card = state.exercises.single().copy(
            partnerTitle = "Weighted Plank", partnerTracking = TrackingType.TIMED,
            rows = state.exercises.single().rows.map { it.copy(partner = PartnerRowState(25.0, 0, seconds = 45)) },
        )
        show(state.copy(exercises = listOf(card)))
        rule.onNodeWithTag("workout-half-${Slot.SS}")
            .assertContentDescriptionEquals("Weighted Plank, next, 45 s at 25 lb, open sets")
    }

    @Test fun missingPartnerRemainsVisibleAsNotAvailable() {
        val state = workoutSetSupersetFixture()
        val card = state.exercises.single().copy(rows = state.exercises.single().rows.map { it.copy(partner = null) })
        show(state.copy(exercises = listOf(card)))
        rule.onNodeWithTag("workout-half-${Slot.SS}")
            .assertContentDescriptionEquals("Not available, next, open sets")
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
        show(workoutSetSingleFixture().copy(rest = workoutRest(), workout = workoutSetSingleFixture().workout?.copy(undo = undo())), workoutActions().copy(
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
        show(workoutSetSingleFixture().copy(workout = workoutSetSingleFixture().workout?.copy(undo = undo())), workoutActions().copy(onUndoLastLog = { undos++ }))
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
        ), workoutActions().copy(onNextWorkoutLift = { nexts++ }, onLogFocusedSet = { logs++ }))
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
                workoutActions().copy(onDone = { finishes++ }), DayEditUiState(), workoutEditActions(),
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

    @Test
    @Config(qualifiers = "w320dp-h891dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun supersetLogStaysReachableAtLargeFont() {
        assertSupersetFitsAtLargeFont(workoutSetSupersetFixture(), "DB Bench Press", "Seated Cable Row", "80 × 10", "120 × 10")
    }

    @Test
    @Config(qualifiers = "w320dp-h891dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun supersetSecondHalfLogStaysReachableAtLargeFontWithUndo() {
        val base = workoutSetSupersetFixture()
        val ss = base.copy(exercises = base.exercises.map { card -> card.copy(rows = card.rows.map {
            if (it.index == 1) it.copy(done = true) else it
        }) }, workout = base.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1),
            undo = undo().copy(title = "DB Bench Press", set = LoggedSet(80.0, 10, SetKind.WORK))))
            .withWorkoutRestHint(true, emptyMap())
        assertSupersetFitsAtLargeFont(ss, "Seated Cable Row", "DB Bench Press", "120 × 10", "80 × 10")
        rule.onNodeWithTag("workout-rest").assertIsDisplayed()
        rule.onNodeWithText("Undo DB Bench Press 80 × 10").assertIsDisplayed().assertIsEnabled()
    }

    private fun assertSupersetFitsAtLargeFont(state: DayUiState, nowTitle: String, partnerTitle: String, nowValue: String, partnerValue: String) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme { WorkoutSetHost(state, workoutActions(), DayEditUiState(), workoutEditActions()) }
            }
        }
        rule.onNodeWithText("Log $nowValue").assertIsDisplayed().assertIsEnabled()
        val nowName = rule.onNodeWithText(nowTitle, useUnmergedTree = true)
        val partnerName = rule.onNodeWithText(partnerTitle, useUnmergedTree = true)
        val position = rule.onNodeWithText("Superset, round 2 of 3")
        for (node in listOf(position, nowName, partnerName)) {
            node.performScrollTo().assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertTrue("${layout.layoutInput.text} must allow wrapping", layout.layoutInput.softWrap)
            assertTrue("${layout.layoutInput.text} must allow multiple lines", layout.layoutInput.maxLines > 1)
        }
        rule.onNodeWithTag("workout-partner-card").performScrollTo().assertIsDisplayed()
        val name = partnerName.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val value = rule.onNodeWithText(partnerValue, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(name.bottom <= value.top)
        rule.onNodeWithText("Log $nowValue").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun liftAndHeroUseExistingSheetCallbacks() {
        var sets: Long? = null
        var editor: Pair<Long, Int>? = null
        show(workoutSetSingleFixture(), workoutActions().copy(onOpenLiftSheet = { id, _ -> sets = id }, onOpenSetEditor = { id, index -> editor = id to index }))
        rule.onNodeWithText("All sets").performClick()
        rule.onNodeWithContentDescription("Edit set 3").performClick()
        rule.runOnIdle { assertEquals(1L, sets); assertEquals(1L to 2, editor) }
    }

    @Test fun partialFinishReusesExistingConfirmation() {
        var finishes = 0
        show(workoutSetSingleFixture(), workoutActions().copy(onDone = { finishes++ }))
        rule.onNodeWithText("Finish").performClick()
        rule.onNodeWithText("Finish with 2 of 6 sets?").assertIsDisplayed()
        rule.onNodeWithText("Keep going").performClick()
        rule.runOnIdle { assertEquals(0, finishes) }
    }

    @Test fun finishConfirmationSurvivesSwitchToTimedRepSlot() {
        var state by mutableStateOf(workoutSetSingleFixture())
        rule.setContent { AppTheme { WorkoutSetHost(state) } }
        rule.onNodeWithText("Finish").performClick()
        rule.onNodeWithText("Finish with 2 of 6 sets?").assertIsDisplayed()
        rule.runOnIdle {
            state = state.copy(exercises = state.exercises.map { it.copy(timedRepDurationSeconds = 40) })
        }
        rule.onNodeWithTag("workout-action-filled").assertExists()
        rule.onNodeWithText("Finish with 2 of 6 sets?").assertIsDisplayed()
        rule.onNodeWithText("Keep going").performClick()
    }

    @Test fun editorRemovalShowsSnackbarUndoUnderSetScreen() {
        var state by mutableStateOf(workoutSetSingleFixture())
        var removed by mutableStateOf(emptyList<RemovedSet>())
        var undos = 0
        val actions = workoutActions().copy(
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
        var state by mutableStateOf(workoutSetSingleFixture().copy(rest = workoutRest().copy(remainingSeconds = 62)))
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

    @Test fun timedRepReadyShowsClockAimAndStartsItsOwnSet() {
        var started: Pair<Long, Int>? = null
        var edited: Pair<Long, Int>? = null
        show(workoutSetTimedRepFixture(), workoutActions().copy(
            onStartTimedRep = { id, index -> started = id to index },
            onOpenSetEditor = { id, index -> edited = id to index },
        ))
        rule.onNodeWithText("0:40").assertIsDisplayed()
        rule.onNodeWithText("Aim 20").assertIsDisplayed()
        rule.onNodeWithText("Set 1 of 2").assertIsDisplayed()
        rule.onNodeWithContentDescription("Edit set 1").performClick()
        rule.onNodeWithText("Start").performClick()
        rule.runOnIdle { assertEquals(1L to 0, started); assertEquals(1L to 0, edited) }
    }

    @Test fun timedRepRunningShowsClockAndTonalCancel() {
        var stops = 0
        show(workoutSetTimedRepRunningFixture(), workoutActions().copy(onStopTimedRep = { stops++ }))
        rule.onNodeWithText("0:27").assertIsDisplayed()
        rule.onNodeWithContentDescription("Edit set 1").assertDoesNotExist()
        rule.onNodeWithTag("workout-action-tonal").performClick()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun timedRepStartThenImmediateCancelIsGuarded() {
        var state by mutableStateOf(workoutSetTimedRepFixture())
        var starts = 0
        var stops = 0
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(
            onStartTimedRep = { _, _ -> starts++; state = workoutSetTimedRepRunningFixture() },
            onStopTimedRep = { stops++ },
        )) } }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Start").performClick()
        rule.mainClock.advanceTimeBy(180)
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(1, starts); assertEquals(0, stops) }
    }

    @Test fun dismissedCountCanBeRequestedAgain() {
        var requests = 0
        show(workoutSetTimedRepRunningFixture(awaiting = true), workoutActions().copy(onRequestTimedRepCount = { requests++ }))
        rule.onNodeWithText("0:00").assertIsDisplayed()
        rule.onNodeWithTag("workout-action-filled").assertIsDisplayed()
        rule.onNodeWithText("Enter reps").performClick()
        rule.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun timedRepOnSecondSetKeepsItsRowAfterEarlierSetUnticked() {
        val afterUntick = workoutSetTimedRepRunningFixture(index = 1)
        var state by mutableStateOf(afterUntick.copy(
            exercises = afterUntick.exercises.map { card -> card.copy(rows = card.rows.map {
                it.copy(done = it.index == 0)
            }) }, workout = afterUntick.workout?.copy(focus = WorkoutFocus(1L, Slot.MAIN, 1)),
        ))
        rule.setContent { AppTheme { WorkoutSetHost(state) } }
        rule.onNodeWithText("Set 2 of 2").assertIsDisplayed()
        rule.runOnIdle { state = afterUntick }
        rule.onNodeWithText("Set 2 of 2").assertIsDisplayed()
        rule.onNodeWithText("0:27").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun supersetTimedRepMainShowsClockAndPartner() {
        val base = workoutSetTimedRepFixture()
        val card = base.exercises.single().copy(isSuperset = true, partnerTitle = "Seated Cable Row",
            partnerExerciseId = "cable_row", partnerTracking = TrackingType.WEIGHTED,
            rows = base.exercises.single().rows.map { it.copy(partner = PartnerRowState(120.0, 10)) })
        show(base.copy(exercises = listOf(card)))
        rule.onNodeWithText("0:40").assertIsDisplayed()
        rule.onNodeWithText("Aim 20").assertIsDisplayed()
        rule.onNodeWithTag("workout-half-${Slot.SS}").assertIsDisplayed()
        rule.onNodeWithText("Start").assertIsEnabled()
    }

    @Test fun cardioReadyShowsActivityPositionTargetAndAllSets() {
        var starts = 0
        var sheets = 0
        show(workoutSetCardioFixture(), workoutActions().copy(onStartCardio = { starts++ }, onOpenCardioSets = { sheets++ }))
        rule.onNodeWithText("Road run").assertIsDisplayed()
        rule.onNodeWithText("Work, set 1 of 1").assertIsDisplayed()
        rule.onNodeWithText("5:00").assertIsDisplayed()
        rule.onNodeWithText("All sets").performClick()
        rule.onNodeWithText("Start").performClick()
        rule.runOnIdle { assertEquals(1, starts); assertEquals(1, sheets) }
    }

    @Test fun cardioRunningTimeShowsCountdownAndStopsItsOwnInterval() {
        val state = workoutSetCardioRunningFixture()
        var stopped: CardioSetAction? = null
        show(state, workoutActions().copy(onStopCardio = { stopped = it }))
        rule.onNodeWithText("1:00").assertIsDisplayed()
        rule.onNodeWithTag("workout-action-tonal").performClick()
        rule.runOnIdle { assertEquals(state.cardio?.run?.action(), stopped) }
    }

    @Test fun cardioDistanceShowsElapsedAimReachedAndOutlinedStopWithSharedGuard() {
        val state = workoutSetCardioDistanceFixture()
        var reached: CardioSetAction? = null
        var stopped: CardioSetAction? = null
        show(state, workoutActions().copy(onReachCardioTarget = { reached = it }, onStopCardio = { stopped = it }))
        rule.onNodeWithText("0:27").assertIsDisplayed()
        rule.onNodeWithText("Aim 1:45").assertIsDisplayed()
        rule.onNodeWithText("to 400 m").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Reached 400 m").performClick()
        rule.onNodeWithText("Stop").assertHasClickAction().performClick()
        rule.runOnIdle { assertEquals(state.cardio?.run?.action(), reached); assertEquals(null, stopped) }
        rule.mainClock.advanceTimeBy(700)
        rule.onNodeWithText("Stop").performClick()
        rule.runOnIdle { assertEquals(state.cardio?.run?.action(), stopped) }
    }

    @Test fun automaticRecoverySwitchGuardsStopForSixHundredMilliseconds() {
        val recovery = workoutSetCardioRecoveryFixture()
        val cardio = requireNotNull(recovery.cardio)
        val run = requireNotNull(cardio.run)
        var state by mutableStateOf(recovery.copy(cardio = cardio.copy(run = run.copy(results = emptyList()))))
        val stops = mutableListOf<CardioSetAction>()
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(onStopCardio = { stops += it })) } }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { state = recovery }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Stop").performClick()
        rule.runOnIdle { assertTrue(stops.isEmpty()) }
        rule.mainClock.advanceTimeBy(700)
        rule.onNodeWithText("Recovery, set 2 of 2").assertIsDisplayed()
        rule.onNodeWithText("Stop").performClick()
        rule.runOnIdle { assertEquals(listOf(run.action()), stops) }
    }

    @Test fun cardioDoneShowsSummaryAndNextWhenAnotherLiftRemains() {
        val done = workoutSetCardioDoneFixture()
        var nexts = 0
        show(done.copy(exercises = workoutSetSingleFixture().exercises), workoutActions().copy(onNextWorkoutLift = { nexts++ }))
        rule.onNodeWithText("Done").assertIsDisplayed()
        rule.onNodeWithText("Logged 5:00").assertIsDisplayed()
        rule.onNodeWithText("1 finished · 0 shortened · 0 skipped").assertIsDisplayed()
        rule.onNodeWithText("Next: Barbell Back Squat").performClick()
        rule.runOnIdle { assertEquals(1, nexts) }
    }

    @Test fun cardioDoneWithNothingLeftOffersFinish() {
        show(workoutSetCardioDoneFixture())
        rule.onNodeWithText("Done").assertIsDisplayed()
        rule.onNodeWithText("Logged 5:00").assertIsDisplayed()
        rule.onNodeWithText("Finish day A").assertIsEnabled()
        rule.onNodeWithText("Finish").assertDoesNotExist()
    }

    @Test fun cardioOnlyDoneHasSummaryAndNoActionOrAllZeroLabel() {
        show(workoutSetCardioDoneFixture().copy(exercises = emptyList()))
        rule.onNodeWithText("Road run").assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
        rule.onNodeWithText("All 0 sets logged").assertDoesNotExist()
        rule.onNodeWithTag("workout-action-filled").assertDoesNotExist()
        rule.onNodeWithTag("workout-action-tonal").assertDoesNotExist()
    }

    @Test fun cardioOnlyAllLoggedWithoutSetHistoryShowsDoneSummaryAndNoAction() {
        val base = workoutSetCardioFixture()
        show(base.copy(exercises = emptyList(),
            cardio = requireNotNull(base.cardio).copy(loggedSeconds = 240, loggedSets = emptyList(), run = null),
            workout = WorkoutState(WorkoutSelection.Shown.AllLogged, null, false, null, null)))
        rule.onNodeWithText("Road run").assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
        rule.onNodeWithText("Logged 4:00").assertIsDisplayed()
        rule.onNodeWithText("5:00").assertDoesNotExist()
        rule.onNodeWithText("All 0 sets logged").assertDoesNotExist()
        rule.onNodeWithTag("workout-action-filled").assertDoesNotExist()
        rule.onNodeWithTag("workout-action-tonal").assertDoesNotExist()
    }

    @Test fun cardioSaveFailureBandStaysOnEveryGroupAndRetries() {
        val running = workoutSetCardioRunningFixture()
        val cardio = requireNotNull(running.cardio)
        var state by mutableStateOf(running.copy(cardio = cardio.copy(run = cardio.run?.copy(saveFailed = true))))
        var retries = 0
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(onRetryCardioSave = { retries++ })) } }
        rule.onNodeWithText("Couldn’t save the cardio. The sets are still here.").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        rule.runOnIdle { state = state.copy(workout = workoutSetSingleFixture().workout) }
        rule.onNodeWithTag("workout-cardio-failed").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, retries) }
        rule.runOnIdle { state = state.copy(cardio = cardio.copy(run = null,
            distanceRuns = listOf(requireNotNull(cardio.run).copy(sessionId = 42L, saveFailed = true)))) }
        rule.onNodeWithTag("workout-cardio-failed").assertIsDisplayed()
    }

    @Test fun clockChipReturnsToTimedRepAndReportsPendingCount() {
        val timed = workoutSetTimedRepRunningFixture()
        val event = requireNotNull(timed.timedRep)
        val base = workoutSetSingleFixture()
        var state by mutableStateOf(base.copy(exercises = base.exercises + timed.exercises.single().copy(programExerciseId = 2L),
            timedRep = event.copy(event = event.event.copy(programExerciseId = 2L))))
        val selected = mutableListOf<String>()
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(onSelectWorkoutLift = { selected += it })) } }
        rule.onNodeWithText("Push-Up 0:27").performClick()
        rule.runOnIdle { state = state.copy(timedRep = state.timedRep?.let {
            it.copy(event = it.event.copy(awaitingCount = true, sheetDismissed = true), remainingSeconds = 0)
        }) }
        rule.onNodeWithText("Push-Up, enter reps").performClick()
        rule.runOnIdle { assertEquals(listOf("exercise:2", "exercise:2"), selected)
            state = state.copy(workout = state.workout?.copy(shown = WorkoutSelection.Shown.Group("exercise:2", false))) }
        rule.onNodeWithTag("workout-clock-chip").assertDoesNotExist()
    }

    @Test fun cardioClockChipRemainsAvailableOnShownTimedRepGroupWithPendingCount() {
        val timed = workoutSetTimedRepRunningFixture(awaiting = true)
        val state = timed.copy(cardio = runningCardioFixture(), cardioRunning = true)
        var selected: String? = null
        show(state, workoutActions().copy(onSelectWorkoutLift = { selected = it }))
        rule.onNodeWithText("Road run 1:00").performClick()
        rule.runOnIdle { assertEquals("cardio", selected) }
    }

    @Test fun cardioClockChipReturnsToCardioAndHidesOnItsOwnGroup() {
        val base = workoutSetCardioRunningFixture()
        var state by mutableStateOf(base.copy(workout = workoutSetSingleFixture().workout))
        var selected: String? = null
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(onSelectWorkoutLift = { selected = it })) } }
        rule.onNodeWithText("Road run 1:00").performClick()
        rule.runOnIdle { assertEquals("cardio", selected)
            state = workoutSetCardioDistanceFixture().copy(workout = workoutSetSingleFixture().workout) }
        rule.onNodeWithText("Road run 0:27").assertIsDisplayed()
        rule.runOnIdle { state = base }
        rule.onNodeWithTag("workout-clock-chip").assertDoesNotExist()
    }

    @Test fun emptyDayOffersEditDay() {
        var editing = false
        rule.setContent { AppTheme { WorkoutSetHost(workoutSetEmptyFixture(),
            dayEditActions = workoutEditActions().copy(onEditorOpen = { editing = it })) } }
        rule.onNodeWithText("No lifts in day A yet.").assertIsDisplayed()
        rule.onNodeWithText("Edit day").performClick()
        rule.onNodeWithTag("workout-action-filled").assertDoesNotExist()
        rule.runOnIdle { assertTrue(editing) }
    }

    @Test fun finishWithOnlyCardioLeftConfirmsWithoutTheActivity() {
        var finishes = 0
        show(workoutSetCardioFixture(), workoutActions().copy(onDone = { finishes++ }))
        rule.onNodeWithText("Finish").performClick()
        rule.onNodeWithText("Finish without Road run?").assertIsDisplayed()
        rule.onNode(hasText("Finish") and hasAnyAncestor(isDialog())).performClick()
        rule.runOnIdle { assertEquals(1, finishes) }
    }

    @Test fun cardioSheetShowsStatusesAndUnsavedDistanceIdentity() {
        val base = workoutSetCardioFixture()
        val block = CardioBlock(CardioActivity.ROAD_RUN, List(4) { CardioSetSpec(CardioRole.WORK, Measure.Time(300)) })
        val json = CardioBlockJson.encode(block)
        val run = CardioRun(1L, "Plan", "A", 0, json, json, false, null,
            results = listOf(SavedCardioResult("FINISHED", 300), SavedCardioResult("SHORTENED", 240, 350), SavedCardioResult("SKIPPED", 0)))
        val entry = run.copy(drafts = listOf(CardioDistanceDraft(1, true, dismissed = true)))
        var requested: CardioSetAction? = null
        var closes = 0
        show(base.copy(cardio = base.cardio?.copy(block = block, run = run, distanceRuns = listOf(entry)),
            openSheet = OpenSheet("A", OpenSheet.Kind.CARDIO_SETS, 0L, 0)), workoutActions().copy(
                onCloseAllSets = { closes++ }, onRequestCardioDistance = { requested = it }))
        for (status in listOf("Finished", "Shortened, 350 m", "Skipped", "Now")) {
            rule.onNodeWithText(status).assertIsDisplayed()
        }
        rule.onNodeWithText("Road run · Set 2").assertIsDisplayed()
        rule.onNodeWithText("Enter distance").performClick()
        rule.runOnIdle { assertEquals(1, closes); assertEquals(entry.action(1), requested) }
    }

    @Test fun cardioSheetSkipAddAndEndUseExistingActionsAndConfirmation() {
        var state by mutableStateOf(workoutSetCardioFixture().copy(openSheet = OpenSheet("A", OpenSheet.Kind.CARDIO_SETS, 0L, 0)))
        var skips: CardioSetAction? = null
        var adds = 0
        var ends = 0
        rule.setContent { AppTheme { WorkoutSetHost(state, workoutActions().copy(
            onCloseAllSets = { state = state.copy(openSheet = null) }, onSkipCardioSet = { skips = it },
            onAddCardioSet = { adds++ }, onEndCardioBlock = { ends++ },
        )) } }
        rule.onNodeWithText("Add set").performClick()
        rule.onNodeWithText("Skip set").performClick()
        rule.runOnIdle {
            assertEquals(1, adds); assertEquals(CardioSetAction("A", null, 0), skips)
            state = state.copy(openSheet = OpenSheet("A", OpenSheet.Kind.CARDIO_SETS, 0L, 0))
        }
        rule.onNodeWithText("End cardio").performClick()
        rule.onNodeWithText("End cardio?").assertIsDisplayed()
        rule.onNodeWithText("End and log").performClick()
        rule.runOnIdle { assertEquals(1, ends) }
    }

    @Test fun setScreenKeepsScreenOnForTimedRepAndCardioAndClearsOnDisposal() {
        var state by mutableStateOf(workoutSetTimedRepFixture())
        var visible by mutableStateOf(true)
        var view: View? = null
        rule.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            if (visible) AppTheme { WorkoutSetHost(state) }
        }
        rule.runOnIdle { assertFalse(requireNotNull(view).keepScreenOn); state = workoutSetTimedRepRunningFixture() }
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.runOnIdle { assertTrue(requireNotNull(view).keepScreenOn); state = workoutSetTimedRepRunningFixture(awaiting = true) }
        rule.runOnIdle { assertFalse(requireNotNull(view).keepScreenOn); state = workoutSetCardioRunningFixture() }
        rule.onNodeWithTag("workout-action-tonal").assertIsDisplayed()
        rule.runOnIdle { assertTrue(requireNotNull(view).keepScreenOn); visible = false }
        rule.runOnIdle { assertFalse(requireNotNull(view).keepScreenOn) }
    }

    @Test fun runningClockDigitsStayOutsideLiveRegions() {
        var state by mutableStateOf(workoutSetTimedRepRunningFixture())
        rule.setContent { AppTheme { WorkoutSetHost(state) } }
        fun assertClockOutsideLiveRegion() {
            var node: SemanticsNode? = rule.onNodeWithTag("workout-hero", useUnmergedTree = true).fetchSemanticsNode()
            while (node != null) {
                assertFalse(node.config.contains(SemanticsProperties.LiveRegion))
                node = node.parent
            }
        }
        assertClockOutsideLiveRegion()
        rule.runOnIdle { state = workoutSetCardioRunningFixture() }
        assertClockOutsideLiveRegion()
        rule.onNodeWithContentDescription("Work, set 1 of 1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.runOnIdle { state = workoutSetCardioRecoveryFixture() }
        assertClockOutsideLiveRegion()
    }

    private fun show(state: DayUiState, actions: DayActions = workoutActions()) {
        rule.setContent { AppTheme { WorkoutSetHost(state, actions, DayEditUiState(), workoutEditActions()) } }
    }

    private fun undo() = UndoOffer(1L, Slot.MAIN, 1, "Barbell Back Squat", LoggedSet(165.0, 5, SetKind.RAMP), TrackingType.WEIGHTED, WeightUnit.LB, null)
}
