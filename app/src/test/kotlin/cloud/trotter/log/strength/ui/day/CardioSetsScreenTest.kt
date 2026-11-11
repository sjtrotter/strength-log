package cloud.trotter.log.strength.ui.day

import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CardioSetsScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun block() = CardioBlock(CardioActivity.TRACK_RUN, listOf(
        CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105)),
        CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)),
    ))

    private fun run(prescription: CardioBlock = block()): CardioRun {
        val json = CardioBlockJson.encode(prescription)
        return CardioRun(1L, "Plan", "D", 0, json, json, false, null,
            startedAt = 1_000, wallStart = 1_000, elapsedStart = 1_000, bootCount = 1)
    }

    @Test fun entryIsBlankWithoutKeyboardFocusAndOnlyPositiveMetresOrBlankEnableDone() {
        var run by mutableStateOf(run().copy(wallStart = null, elapsedStart = null, bootCount = null,
            results = listOf(SavedCardioResult("SHORTENED", 23)),
            drafts = listOf(CardioDistanceDraft(0, stopped = true))))
        var saved = 0
        var delivered: CardioSetAction? = null
        val actions = workoutActions().copy(
            onCardioDistanceChange = { text -> run = run.copy(drafts = listOf(run.drafts.single().copy(text = text))) },
            onSaveCardioDistance = { delivered = it; saved++ },
        )
        rule.setContent { AppTheme { CardioDistanceSheet(run, 0, actions, DistanceUnit.MI) } }
        rule.onNodeWithText("Set stopped").assertExists()
        rule.onNodeWithText("Track run · Set 1").assertExists()
        rule.onNodeWithText("Distance (m)").assertIsNotFocused()
        rule.onNodeWithText("Continue without distance").assertIsEnabled()
        rule.onNodeWithText("Distance (m)").performTextReplacement("0")
        rule.onNodeWithText("Save distance").assertIsNotEnabled()
        rule.onNodeWithText("Enter a distance greater than 0").assertExists()
        rule.onNodeWithContentDescription("Increase distance").performClick()
        rule.onNodeWithText("Save distance").assertIsEnabled()
        rule.onNodeWithText("Distance (m)").performTextReplacement("")
        rule.onNodeWithText("Continue without distance").performClick()
        rule.runOnIdle {
            assertEquals(1, saved)
            assertEquals(run.action(0), delivered)
        }
    }

    @Test fun roadDistanceSheetLabelsAndEditsTheProvidedUnit() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(300))))
        var run by mutableStateOf(run(block).copy(wallStart = null, elapsedStart = null, bootCount = null,
            results = listOf(SavedCardioResult("FINISHED", 300)), drafts = listOf(CardioDistanceDraft(0, false))))
        val actions = workoutActions().copy(onCardioDistanceChange = { text ->
            val meters = text.toDoubleOrNull()?.let(DistanceUnit.KM::toMetres)?.toString().orEmpty()
            run = run.copy(drafts = listOf(run.drafts.single().copy(text = meters)))
        })
        rule.setContent { AppTheme { CardioDistanceSheet(run, 0, actions, DistanceUnit.KM) } }
        rule.onNodeWithText("Distance (km)").performTextReplacement("5.25")
        rule.onNodeWithText("Save distance").assertIsEnabled()
        rule.runOnIdle { assertEquals(5250, run.drafts.single().metres()) }
    }

    @Test fun legacyDistanceBelowDisplayPrecisionStillEnablesSave() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(300))))
        val run = run(block).copy(results = listOf(SavedCardioResult("FINISHED", 300)),
            drafts = listOf(CardioDistanceDraft(0, false, "1")))
        rule.setContent { AppTheme { CardioDistanceSheet(run, 0, workoutActions(), DistanceUnit.MI) } }

        rule.onNodeWithText("Distance (mi)").assertTextContains("0")
        rule.onNodeWithText("Save distance").assertIsEnabled()
        rule.onNodeWithText("Enter a distance greater than 0").assertDoesNotExist()
    }

    @Test fun legacyDistanceAboveEntryLimitDisplaysAndEnablesSave() {
        val block = CardioBlock(CardioActivity.OUTDOOR_RIDE, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(300))))
        val run = run(block).copy(results = listOf(SavedCardioResult("FINISHED", 300)),
            drafts = listOf(CardioDistanceDraft(0, false, "250000")))
        var saved: CardioSetAction? = null
        val actions = workoutActions().copy(onSaveCardioDistance = { saved = it })
        rule.setContent { AppTheme { CardioDistanceSheet(run, 0, actions, DistanceUnit.KM) } }

        rule.onNodeWithText("Distance (km)").assertTextContains("250")
        rule.onNodeWithText("Save distance").assertIsEnabled().performClick()
        rule.onNodeWithText("Enter a distance greater than 0").assertDoesNotExist()
        rule.runOnIdle { assertEquals(run.action(0), saved) }
    }

    @Test fun aRunPastItsTimeEndOffersTheDistanceAtItsEnd() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WORK, Measure.Time(1500), Measure.Distance(5000)),
        ))
        var run by mutableStateOf(run(block).copy(wallStart = null, elapsedStart = null, bootCount = null,
            results = listOf(SavedCardioResult("FINISHED", 1690)),
            drafts = listOf(CardioDistanceDraft(0, false, "5600"))))
        var reading by mutableStateOf<CardioReadingInput?>(null)
        val actions = workoutActions().copy(onCardioReadingChange = { text ->
            reading = CardioReadingInput(run.runId, 0, text, DistanceUnit.KM)
        })
        rule.setContent { AppTheme { CardioDistanceSheet(run, 0, actions, DistanceUnit.KM, reading = reading) } }
        rule.onNodeWithText("Distance at 25:00").assertExists().performTextReplacement("5.7")
        rule.onNodeWithText("No more than your total distance").assertExists()
        rule.onNodeWithText("Save distance").assertIsNotEnabled()
        rule.onNodeWithText("Distance at 25:00").performTextReplacement("0")
        rule.onAllNodesWithText("Enter a distance greater than 0").assertCountEquals(1)
        rule.onNodeWithText("Save distance").assertIsNotEnabled()
        rule.onNodeWithText("Distance at 25:00").performTextReplacement("5.05")
        rule.onNodeWithText("Save distance").assertIsEnabled()
        rule.onNodeWithContentDescription("Increase distance at 25:00").assertExists()
        rule.runOnIdle { run = run.copy(drafts = listOf(CardioDistanceDraft(0, false, ""))) }
        rule.onNodeWithText("Distance at 25:00").assertDoesNotExist()
        rule.runOnIdle {
            run = run.copy(results = listOf(SavedCardioResult("FINISHED", 1500)),
                drafts = listOf(CardioDistanceDraft(0, false, "5600")))
        }
        rule.onNodeWithText("Distance at 25:00").assertDoesNotExist()
    }

    @Test fun aDistanceReadingStepperStaysWithinATotalEnteredInAnotherUnit() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WORK, Measure.Time(1500)),
        ))
        val run = run(block).copy(wallStart = null, elapsedStart = null, bootCount = null,
            results = listOf(SavedCardioResult("FINISHED", 1690)),
            drafts = listOf(CardioDistanceDraft(0, false, "5000")))
        var reading by mutableStateOf<CardioReadingInput?>(null)
        val actions = workoutActions().copy(onCardioReadingChange = { text ->
            reading = CardioReadingInput(run.runId, 0, text, DistanceUnit.MI)
        })
        rule.setContent { AppTheme { CardioDistanceSheet(run, 0, actions, DistanceUnit.MI, reading = reading) } }
        rule.onNodeWithText("Distance at 25:00").performTextReplacement("3.1")
        repeat(2) { rule.onNodeWithContentDescription("Increase distance at 25:00").performClick() }
        rule.onNodeWithText("Distance at 25:00").assertTextContains("3.1")
        rule.onNodeWithText("No more than your total distance").assertDoesNotExist()
        rule.onNodeWithText("Save distance").assertIsEnabled()
        rule.runOnIdle {
            assertEquals("3.1", reading?.text)
            assertEquals(4989, DistanceUnit.MI.toMetres(requireNotNull(reading).text.toDouble()))
        }
    }

    @Test fun aStoppedSetPastItsDistanceEndOffersTheTimeAtItsEnd() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WORK, Measure.Distance(2414), Measure.Time(960)),
        ))
        var run by mutableStateOf(run(block).copy(wallStart = null, elapsedStart = null, bootCount = null,
            results = listOf(SavedCardioResult("SHORTENED", 1000)),
            drafts = listOf(CardioDistanceDraft(0, stopped = true, text = "2736"))))
        var reading by mutableStateOf<CardioReadingInput?>(null)
        val actions = workoutActions().copy(onCardioReadingChange = { text ->
            reading = CardioReadingInput(run.runId, 0, text, DistanceUnit.MI)
        })
        rule.setContent { AppTheme { CardioDistanceSheet(run, 0, actions, DistanceUnit.MI, reading = reading) } }
        rule.onNodeWithText("Time at 1.5 mi").assertExists().performTextReplacement("16:41")
        rule.onNodeWithText("No more than your total time").assertExists()
        rule.onNodeWithText("Save distance").assertIsNotEnabled()
        rule.onNodeWithText("Time at 1.5 mi").performTextReplacement("0:00")
        rule.onNodeWithText("Save distance").assertIsNotEnabled()
        rule.onNodeWithText("No more than", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Time at 1.5 mi").performTextReplacement("15:00")
        rule.onNodeWithText("Save distance").assertIsEnabled()
        rule.runOnIdle { run = run.copy(drafts = listOf(CardioDistanceDraft(0, true, "2414"))) }
        rule.onNodeWithText("Time at 1.5 mi").assertDoesNotExist()
    }

    @Test fun cardioSheetReportsStatusesAndThePrescribedAim() {
        val run = run().copy(wallStart = null, elapsedStart = null, bootCount = null,
            results = listOf(SavedCardioResult("FINISHED", 127, 400)))
        val cardio = CardioCardState("Track run", "", false, block = run.block, run = run)
        rule.setContent { AppTheme { WorkoutCardioSheet(cardio, "D", workoutActions(), {}, true) } }
        rule.onNodeWithText("Finished").assertExists()
        rule.onNodeWithText("400 m · aim 1:45").assertExists()
        rule.onNodeWithText("Recovery").assertExists()
        rule.onNodeWithText("Now").assertExists()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Add set").assertIsEnabled()
    }

    @Test fun loggedTrackRowsKeepMetersAfterPlanChangesToRoadRun() {
        val distance = block().sets.first()
        val timed = CardioSetSpec(CardioRole.WORK, Measure.Time(120), Measure.Distance(800))
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(distance, timed))
        val cardio = CardioCardState("Road run", "", false, block = block,
            loggedActivity = CardioActivity.TRACK_RUN,
            loggedSets = listOf(
                CardioSetResult(distance, CardioSetStatus.FINISHED, 100, 400, DistanceSource.MANUAL),
                CardioSetResult(timed, CardioSetStatus.SHORTENED, 60, 300, DistanceSource.MANUAL),
            ))
        rule.setContent { AppTheme { WorkoutCardioSheet(cardio, "D", workoutActions(), {}) } }

        rule.onNodeWithText("Track run").assertExists()
        rule.onNodeWithText("Road run").assertDoesNotExist()
        rule.onNodeWithText("400 m · aim 1:45").assertExists()
        rule.onNodeWithText("2:00 · aim 800 m").assertExists()
        rule.onNodeWithText("Shortened, 300 m").assertExists()
        rule.onNodeWithText("0.25 mi · aim 1:45").assertDoesNotExist()
        rule.onNodeWithText("Shortened, 0.19 mi").assertDoesNotExist()
    }

    @Test fun addSetIsDisabledWhenAnotherDayOwnsExecution() {
        val cardio = CardioCardState("Track run", "", false, block = block(), canAddSet = false)
        var additions = 0
        rule.setContent { AppTheme {
            WorkoutCardioSheet(cardio, "D", workoutActions().copy(onAddCardioSet = { additions++ }), {}, false)
        } }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Add set").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, additions) }
    }

    @Test fun distanceAndRecoveryHoldTheViewAwakeAndDisposalClearsIt() {
        var visible by mutableStateOf(true)
        val distanceRun = run()
        var state by mutableStateOf(workoutSetCardioFixture().copy(exercises = emptyList(),
            cardioRunning = true, executionBusy = true,
            cardio = CardioCardState("Track run", "", false, block = distanceRun.block, run = distanceRun)))
        var view: View? = null
        rule.setContent { AppTheme {
            val currentView = LocalView.current
            SideEffect { view = currentView }
            if (visible) WorkoutSetHost(state, workoutActions(), DayEditUiState(), workoutEditActions())
        } }
        rule.runOnIdle {
            assertTrue(requireNotNull(view).keepScreenOn)
            val recoveryBlock = CardioBlock(CardioActivity.TRACK_RUN, listOf(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120))))
            val recovery = run(recoveryBlock)
            state = state.copy(cardio = state.cardio?.copy(block = recoveryBlock, run = recovery))
        }
        rule.runOnIdle { assertTrue(requireNotNull(view).keepScreenOn); visible = false }
        rule.runOnIdle { assertFalse(requireNotNull(view).keepScreenOn) }
    }
    @Test
    fun `planned detail sums timed ends including warmup and recovery regardless of aims`() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
            CardioSetSpec(CardioRole.WORK, Measure.Time(120), Measure.Distance(400), hard = true),
            CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)),
        ))
        assertEquals("9:00", block.plannedTimeDetail())
    }

    @Test
    fun `a distance end leaves planned detail empty even with a timed aim`() {
        val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(
            CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
        ))
        assertEquals("", block.plannedTimeDetail())
    }
}
