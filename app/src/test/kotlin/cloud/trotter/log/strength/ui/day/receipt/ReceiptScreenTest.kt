package cloud.trotter.log.strength.ui.day.receipt

import cloud.trotter.log.strength.domain.progression.SprintDose
import cloud.trotter.log.strength.ui.day.sprintFortyYardReceiptFixture
import cloud.trotter.log.strength.ui.day.sprintReceiptFixture
import cloud.trotter.log.strength.ui.day.sprintReceiptProgressFixture

import android.animation.ValueAnimator
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertHeightIsAtLeast
import cloud.trotter.log.strength.data.serialization.RunResult
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.LadderHold
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.ui.day.CardioNextTime
import cloud.trotter.log.strength.ui.day.CardioIncrease
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.records.PrKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.components.NoteSheet
import cloud.trotter.log.strength.ui.components.LocalDistanceUnit
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.FinishFailedScreen
import cloud.trotter.log.strength.ui.day.FinishingScreen
import cloud.trotter.log.strength.ui.day.SessionReceiptScreen
import cloud.trotter.log.strength.ui.day.SkippedLift
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ReceiptScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private var originalDurationScale = 1f
    private var backDispatcher: OnBackPressedDispatcher? = null

    @Before
    fun saveAnimatorScale() {
        originalDurationScale = ValueAnimator::class.java.getDeclaredMethod("getDurationScale").invoke(null) as Float
        setAnimatorScale(1f)
    }

    @After
    fun restoreAnimatorScale() {
        setAnimatorScale(originalDurationScale)
    }

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @Test fun `a steady row shows pace and last time`() {
        rule.setContent { AppTheme {
            SessionReceiptScreen(rowerPaceReceiptFixture(), {}, {},
                progress = ReceiptProgressState(cardioLastTimes = listOf(CardioLastTime(null, 3412, true))))
        } }
        rule.onNodeWithText("Pace 2:10 /500 m", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Up from 3,412 m last time", useUnmergedTree = true).assertExists()
    }

    @Test fun `a record row shows pace and keeps its mark without last time`() {
        rule.setContent { AppTheme {
            SessionReceiptScreen(rowerPaceReceiptFixture(record = true), {}, {},
                progress = ReceiptProgressState(cardioLastTimes = listOf(CardioLastTime(null, 3412, true))))
        } }
        rule.onNodeWithText("Farther PR", useUnmergedTree = true).assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("3,412 m", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(TextDecoration.LineThrough, layouts.single().layoutInput.style.textDecoration)
        rule.onNodeWithText("Pace 2:10 /500 m", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Up from 3,412 m last time", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("15 minutes 4 seconds. Distance, 3,485 meters. Farther record, up from 3,412 meters. Pace 2 minutes 10 seconds per 500 meters.").assertExists()
    }

    @Test fun `a distance row says faster than last time`() {
        val row = CardioReceiptRow(CardioRole.WORK, 1, null, 2000,
            listOf(CardioInterval(CardioSetStatus.FINISHED, 477, 2000)), emptyList(), null)
        rule.setContent { AppTheme {
            SessionReceiptScreen(rowerPaceReceiptFixture().copy(cardioRows = listOf(row)), {}, {},
                progress = ReceiptProgressState(cardioLastTimes = listOf(CardioLastTime(492, null, true))))
        } }
        rule.onNodeWithText("Faster than 8:12 last time", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("2,000 meters. Time, 7 minutes 57 seconds. Pace 1 minute 59 seconds per 500 meters. Faster than 8 minutes 12 seconds last time.").assertExists()
    }

    @Test fun `rides show no pace`() {
        setReceipt(rowerPaceReceiptFixture().copy(activity = CardioActivity.STATIONARY_BIKE))
        rule.onAllNodesWithText("Pace", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun `a finished distance receipt shows pace once untimed sets load`() {
        val result = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(2000)),
            CardioSetStatus.FINISHED, 477, metresAtEnd = 2000, secondsAtEnd = 477)
        val receipt = rowerPaceReceiptFixture().copy(cardioRows = ReceiptBuilder.cardio(
            CardioActivity.ROWER, listOf(result), emptyMap()))
        assertNull(receipt.cardioRows.single().intervals.single().metres)
        val progress = mutableStateOf(ReceiptProgressState())
        rule.setContent { AppTheme { SessionReceiptScreen(receipt, {}, {}, progress = progress.value) } }
        // #531: The saved interval cannot distinguish untimed work from a timed sprint until its set loads.
        rule.onAllNodesWithText("Pace", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.runOnIdle { progress.value = ReceiptProgressState(cardioSets = listOf(listOf(result))) }
        rule.onNodeWithText("Pace 1:59 /500 m", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("2,000 meters. Time, 7 minutes 57 seconds. Pace 1 minute 59 seconds per 500 meters.").assertExists()
    }

    @Test fun `a distance receipt pairs planned meters with at end seconds`() {
        val result = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(2000)),
            CardioSetStatus.FINISHED, 510, metresAtEnd = 2000, secondsAtEnd = 477)
        val receipt = rowerPaceReceiptFixture().copy(cardioRows = ReceiptBuilder.cardio(
            CardioActivity.ROWER, listOf(result), emptyMap()))
        rule.setContent { AppTheme {
            SessionReceiptScreen(receipt, {}, {}, progress = ReceiptProgressState(cardioSets = listOf(listOf(result))))
        } }
        rule.onNodeWithText("Pace 1:59 /500 m", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("2,000 meters. Time, 8 minutes 30 seconds. Pace 1 minute 59 seconds per 500 meters.").assertExists()
    }

    @Test fun `a legacy finished distance receipt shows planned distance pace`() {
        val result = legacyDistancePaceResultFixture()
        val receipt = rowerPaceReceiptFixture().copy(cardioRows = ReceiptBuilder.cardio(
            CardioActivity.ROWER, listOf(result), emptyMap()))
        rule.setContent { AppTheme {
            SessionReceiptScreen(receipt, {}, {}, progress = ReceiptProgressState(cardioSets = listOf(listOf(result))))
        } }
        rule.onNodeWithText("Pace 1:59 /500 m", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("2,000 meters. Time, 7 minutes 57 seconds. Pace 1 minute 59 seconds per 500 meters.").assertExists()
    }

    @Test fun `a shortened distance receipt without meters has no pace`() {
        val result = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(2000)),
            CardioSetStatus.SHORTENED, 400)
        setReceipt(rowerPaceReceiptFixture().copy(cardioRows = ReceiptBuilder.cardio(
            CardioActivity.ROWER, listOf(result), emptyMap())))
        rule.onAllNodesWithText("Pace", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun `a timed sprint receipt has no wall clock pace`() {
        val result = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(400)),
            CardioSetStatus.FINISHED, 100, metresAtEnd = 400, secondsAtEnd = 100,
            timing = SprintTime(5500, TimingMethod.HAND, StartProtocol.STANDING))
        val receipt = cardioReceiptFixture().copy(cardioRows = ReceiptBuilder.cardio(
            CardioActivity.TRACK_RUN, listOf(result), emptyMap()))
        val progress = mutableStateOf(ReceiptProgressState())
        rule.setContent { AppTheme {
            SessionReceiptScreen(receipt, {}, {}, progress = progress.value)
        } }
        rule.onAllNodesWithText("Pace", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Pace", substring = true).assertCountEquals(0)
        rule.runOnIdle { progress.value = ReceiptProgressState(cardioSets = listOf(listOf(result))) }
        rule.onAllNodesWithText("Pace", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Pace", substring = true).assertCountEquals(0)
    }

    @Test fun `the spoken row includes pace and last time`() {
        rule.setContent { AppTheme {
            SessionReceiptScreen(rowerPaceReceiptFixture(), {}, {},
                progress = ReceiptProgressState(cardioLastTimes = listOf(CardioLastTime(null, 3412, true))))
        } }
        rule.onNodeWithContentDescription("15 minutes 4 seconds. Distance, 3,485 meters. Pace 2 minutes 10 seconds per 500 meters. Up from 3,412 meters last time.").assertExists()
    }

    @Test fun `equal or worse rows label last time`() {
        rule.setContent { AppTheme {
            SessionReceiptScreen(rowerPaceReceiptFixture(), {}, {},
                progress = ReceiptProgressState(cardioLastTimes = listOf(CardioLastTime(null, 3500, false))))
        } }
        rule.onNodeWithText("Last time 3,500 m", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("15 minutes 4 seconds. Distance, 3,485 meters. Pace 2 minutes 10 seconds per 500 meters. Last time 3,500 meters.").assertExists()
    }

    @Test fun `a forty yard sprint receipt uses the event label`() {
        val (receipt, progress) = sprintFortyYardReceiptFixture()
        rule.setContent { AppTheme { SessionReceiptScreen(receipt, {}, {}, progress = progress) } }
        rule.onNodeWithText("40 yd", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // The Next time line exposes only its spoken form (clearAndSetSemantics): it says the event in yards.
        rule.onNodeWithContentDescription("Next time, 2 × 40 yd.", useUnmergedTree = true).assertExists()
        assertTrue(rule.onAllNodesWithText("37 m", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithContentDescription("37 m", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes().isEmpty())
    }

    @Test fun `a non sprint receipt keeps 37 metres`() {
        val work = CardioSetSpec(CardioRole.WORK, Measure.Distance(37))
        val results = listOf(CardioSetResult(work, CardioSetStatus.FINISHED, 5))
        val receipt = timeTargetFixture().copy(activity = CardioActivity.TRACK_RUN,
            cardioRows = ReceiptBuilder.cardio(CardioActivity.TRACK_RUN, results, emptyMap()))
        rule.setContent { AppTheme { SessionReceiptScreen(receipt, {}, {}) } }
        rule.onNodeWithText("37 m", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("40 yd", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `next time shows under each lift`() {
        val bench = ReceiptRow("Bench", TrackingType.WEIGHTED, ReceiptRow.Shape.STRAIGHT,
            List(3) { ReceiptSet(215.0, 5, 0) }, listOf(ValueLine(215.0, listOf(0, 1, 2))),
            0, PlannedSets(3, 215.0, 5, 0), null)
        val row = bench.copy(name = "Row", sets = listOf(5, 5, 4).map { ReceiptSet(215.0, it, 0) })
        val receipt = receiptFixture().copy(groups = listOf(ReceiptGroup(3, listOf(bench, row))))
        setReceipt(receipt, nextTimes = ReceiptNextTimes(receipt.sessionId, listOf(
            ReceiptNextTime(0, 0, 220.0, 215.0), ReceiptNextTime(0, 1, null, 215.0),
        )))

        rule.onNodeWithText("Next time: 220 lb (+5)", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Next time: 215 lb", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Bench. 215 pounds: 5, 5 and 5 reps. Next time, 220 pounds, up 5 pounds.").assertExists()
        rule.onNodeWithContentDescription("Row. 215 pounds: 5, 5 and 4 reps. Next time, 215 pounds.").assertExists()
    }

    @Test
    fun `a top-set row labels and speaks its two back-offs`() {
        val top = receiptFixture().groups.first().rows.single()
        val row = top.copy(sets = listOf(ReceiptSet(275.0, 5, 0), ReceiptSet(250.0, 5, 0), ReceiptSet(250.0, 5, 0)),
            lines = listOf(ValueLine(275.0, listOf(0)), ValueLine(250.0, listOf(1, 2))), record = null)
        val receipt = receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row))))
        setReceipt(receipt, backoffLabels = ReceiptBackoffLabels(receipt.sessionId, listOf(ReceiptBackoffLine(0, 0, 1))))

        rule.onAllNodesWithText("Top set", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText("Back-offs", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("250 lb × 5, 5", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Barbell Back Squat. Top set, 275 pounds for 5 reps. Back-offs, 250 pounds: 5 and 5 reps.")
            .assertHasNoClickAction()
    }

    @Test
    fun `a top-set row without back-offs is unchanged`() {
        setReceipt(receiptFixture().copy(groups = receiptFixture().groups.take(1)))
        rule.onAllNodesWithText("Back-offs", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Barbell Back Squat. Top set, 245 pounds for 5 reps. Weight record, up from 240 pounds for 5 reps.")
            .assertHasNoClickAction()
    }

    @Test
    fun `back-off labels for another session are ignored`() {
        val top = receiptFixture().groups.first().rows.single()
        val row = top.copy(sets = top.sets + ReceiptSet(185.0, 8, 0), lines = top.lines + ValueLine(185.0, listOf(1)))
        val receipt = receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row))))
        setReceipt(receipt, backoffLabels = ReceiptBackoffLabels(receipt.sessionId + 1, listOf(ReceiptBackoffLine(0, 0, 1))))
        rule.onAllNodesWithText("Back-offs", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Barbell Back Squat. Top set, 245 pounds for 5 reps. 185 pounds: 8 reps. Weight record, up from 240 pounds for 5 reps.")
            .assertHasNoClickAction()
    }

    @Test
    fun `a ramped row speaks its collapsed working-set count`() {
        val top = receiptFixture().groups.first().rows.single()
        val row = top.copy(sets = top.sets + listOf(185.0, 175.0, 165.0).map { ReceiptSet(it, 8, 0) },
            lines = top.lines + ValueLine(185.0, listOf(1)), lighterSets = 2)
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row)))))

        rule.onAllNodesWithText("Top set", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText("245 lb × 5", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("185 lb × 8", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("and 2 lighter sets", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Barbell Back Squat. Top set, 245 pounds for 5 reps. 185 pounds: 8 reps. and 2 lighter sets. Weight record, up from 240 pounds for 5 reps.")
            .assertHasNoClickAction()
    }

    @Test
    fun `next times for another session are ignored`() {
        val receipt = receiptFixture().copy(groups = receiptFixture().groups.take(1))
        setReceipt(receipt, nextTimes = ReceiptNextTimes(receipt.sessionId + 1, listOf(ReceiptNextTime(0, 0, 250.0, 245.0))))

        rule.onAllNodesWithText("Next time", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Next time", substring = true).assertDoesNotExist()
    }

    @Test
    fun headerNamesTheFinishedDayPlanDateAndDuration() {
        setReceipt()
        listOf("Squat focus, saved.", "Four-day strength", "Tuesday, Nov 3, 58 min").forEach {
            rule.onNodeWithText(it).assertExists()
        }
    }

    @Test
    fun partialReceiptSaysSavedWithTheExactLoggedAndPlannedCount() {
        val row = receiptFixture().groups.first().rows.single().copy(
            sets = List(2) { ReceiptSet(130.0, 5, 0) }, lines = listOf(ValueLine(130.0, listOf(0, 1))),
            planned = PlannedSets(6, 130.0, 5, 0), record = null,
        )
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row))), allPlansDone = true))
        rule.onNodeWithText("Squat focus, saved.").assertExists()
        rule.onNodeWithText("2 of 6 sets logged").assertExists()
        rule.onNodeWithText("Squat focus, done.").assertDoesNotExist()
        rule.onNodeWithText("All plans done for today.").assertDoesNotExist()
    }

    @Test
    fun finishCountUsesLiveRowsWhileThePlannedLineKeepsThePrescription() {
        val row = receiptFixture().groups[3].rows.single().copy(
            sets = listOf(ReceiptSet(40.0, 5, 0)), lines = listOf(ValueLine(40.0, listOf(0))),
            planned = PlannedSets(2, 30.0, 8, 0), record = null,
        )
        val receipt = receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row))))
        setReceipt(receipt, setCounts = ReceiptSetCounts(receipt.sessionId, 4, 3))

        rule.onNodeWithText("1 of 4 sets logged").assertExists()
        rule.onNodeWithContentDescription("Dumbbell Curl. 40 pounds: 5 reps.").performClick()
        rule.onNodeWithText("Planned 2 × 8 at 30 lb", useUnmergedTree = true).assertExists()
    }

    @Test
    fun repsOnlyWorkShowsTheWeightedFinishPrescription() {
        val row = ReceiptRow("Dumbbell Curl", TrackingType.REPS, ReceiptRow.Shape.STRAIGHT,
            listOf(ReceiptSet(0.0, 12, 0)), listOf(ValueLine(null, listOf(0))),
            0, PlannedSets(3, 30.0, 8, 0), null)
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row)))))

        rule.onNodeWithContentDescription("Dumbbell Curl. 12 reps.").performClick()
        rule.onNodeWithContentDescription("Set 1, 12 reps").assertExists()
        rule.onNodeWithText("Planned 3 × 8 at 30 lb", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Planned 3 sets of 8 reps at 30 pounds").assertExists()
    }

    @Test
    fun repsOnlyWorkShowsTheTimedFinishPrescription() {
        val row = ReceiptRow("Plank", TrackingType.REPS, ReceiptRow.Shape.STRAIGHT,
            listOf(ReceiptSet(0.0, 12, 0)), listOf(ValueLine(null, listOf(0))),
            0, PlannedSets(3, 0.0, 0, 45), null)
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row)))))

        rule.onNodeWithContentDescription("Plank. 12 reps.").performClick()
        rule.onNodeWithContentDescription("Set 1, 12 reps").assertExists()
        rule.onNodeWithText("Planned 3 × 45s", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Planned 3 holds of 45 seconds").assertExists()
    }

    @Test
    fun oldSkippedReceiptDoesNotInventAPlannedSetCount() {
        val skipped = ReceiptRow("Face Pull", TrackingType.WEIGHTED, ReceiptRow.Shape.SKIPPED,
            emptyList(), emptyList(), 0, null, null)
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(skipped)))))
        rule.onNodeWithText("Squat focus, saved.").assertExists()
        rule.onNodeWithText("Logged 0 sets. Planned set count not recorded.").assertExists()
    }

    @Test
    fun completeReceiptKeepsTheDoneTitle() {
        setReceipt(receiptFixture().let { it.copy(groups = it.groups.take(1)) })
        rule.onNodeWithText("Squat focus, done.").assertExists()
        rule.onNodeWithText("Squat focus, saved.").assertDoesNotExist()
    }

    @Test
    fun skippedFirstSupersetHalfSaysSavedAndSuppressesAllPlansDone() {
        setReceipt(skippedSupersetReceiptFixture())
        rule.onNodeWithText("Squat focus, saved.").assertExists()
        rule.onNodeWithText("3 of 6 sets logged").assertExists()
        rule.onNodeWithText("Squat focus, done.").assertDoesNotExist()
        rule.onNodeWithText("All plans done for today.").assertDoesNotExist()
    }

    @Test
    fun completedReplacementKeepsTheDoneTitle() {
        setReceipt(swappedReceiptFixture(), setCounts = ReceiptSetCounts(1, 3, 0))
        rule.onNodeWithText("Squat focus, done.").assertExists()
        rule.onNodeWithText("Squat focus, saved.").assertDoesNotExist()
        rule.onNodeWithText("5 of 6 sets logged").assertDoesNotExist()
    }

    @Test
    fun partialAuditFixtureShowsFewerLoggedSetsThanPlanned() {
        setReceipt(partialReceiptFixture())
        rule.onNodeWithText("Squat focus, saved.").assertExists()
        rule.onNodeWithText("1 of 6 sets logged").assertExists()
    }

    @Test
    fun skippedCardioSuppressesTheCompletionFooter() {
        val receipt = timeTargetFixture()
        val row = receipt.cardioRows.single().copy(intervals = listOf(CardioInterval(CardioSetStatus.SKIPPED, 0, null)), record = null)
        setReceipt(receipt.copy(cardioRows = listOf(row), allPlansDone = true))
        rule.onNodeWithText("Easy run, saved.").assertExists()
        rule.onNodeWithText("0 of 1 sets logged").assertExists()
        rule.onNodeWithText("All plans done for today.").assertDoesNotExist()
    }

    @Test
    fun unknownDurationSaysItWasNotRecorded() {
        setReceipt(receiptFixture().copy(minutes = null))
        rule.onNodeWithText("Tuesday, Nov 3, Duration not recorded").assertExists()
        rule.onNodeWithText("Tuesday, Nov 3, 58 min").assertDoesNotExist()
    }

    @Test
    fun collapsedSquatSpeaksOneSentenceAndNeverOpens() {
        setReceipt()
        rule.onNodeWithContentDescription(SQUAT).assertExists().assertHasNoClickAction()
        rule.onNodeWithText("Weight PR", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("240 lb × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("245 lb × 5", useUnmergedTree = true).assertExists()
    }

    @Test
    fun curlOpensWithEverySetTheRecordAndThePlan() {
        setReceipt()
        val curl = rule.onNodeWithContentDescription(CURL)
        curl.assertHasClickAction().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        rule.onNodeWithText("Planned 3 × 10 at 35 lb", useUnmergedTree = true).assertDoesNotExist()
        curl.performScrollTo().performClick()
        curl.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        rule.onNodeWithText("Planned 3 × 10 at 35 lb", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("PR, up from 35 lb × 9", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Set 1, 35 pounds for 10 reps, rep record, up from 35 pounds for 9 reps.").assertExists()
        curl.performClick()
        rule.onNodeWithText("Planned 3 × 10 at 35 lb", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun lighterSetsRepsAndHoldsUseTheirOwnValueLinesAndSentences() {
        setReceipt()
        rule.onNodeWithText("and 3 lighter sets", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Dumbbell Lateral Raise. Heaviest set, 25 pounds for 12 reps, and 3 lighter sets.").assertExists()
        rule.onNodeWithText("12 reps, 10 reps, 9 reps", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Pull-up. 12, 10 and 9 reps.").assertExists()
        rule.onNodeWithText("75 s, 70 s", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Hold PR", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Plank. Holds of 1 minute 15 seconds and 1 minute 10 seconds. Hold record on set 1, up from 1 minute 10 seconds.").assertExists()
    }

    @Test
    fun supersetKeepsItsRoundsGuttersAndBothWeightGroups() {
        setReceipt()
        listOf("Superset, 3 rounds", "A1", "A2").forEach { rule.onNodeWithText(it, useUnmergedTree = true).assertExists() }
        rule.onNodeWithContentDescription("Barbell Row. 155 pounds: 8 and 8 reps. 135 pounds: 10 reps.").assertHasClickAction()
    }

    @Test
    fun skippedLiftHasNoClickAction() {
        setReceipt()
        rule.onNodeWithText("Skipped", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Face Pull, skipped.").assertHasNoClickAction()
    }

    @Test
    fun kilogramsConvertBothCurrentAndPreviousValuesAndSpeech() {
        val row = receiptFixture().groups.first().rows.single().copy(
            sets = listOf(ReceiptSet(231.485, 5, 0)),
            lines = listOf(ValueLine(231.485, listOf(0))),
            record = RecordMark(PrKind.WEIGHT, null, 0, ReceiptSet(220.462, 5, 0)),
        )
        setReceipt(receiptFixture().copy(unit = WeightUnit.KG, groups = listOf(ReceiptGroup(null, listOf(row)))))
        rule.onNodeWithText("105 kg × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("100 kg × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Barbell Back Squat. Top set, 105 kilograms for 5 reps. Weight record, up from 100 kilograms for 5 reps.").assertExists()
    }

    @Test
    fun twoRecordKindsHaveOneLabelAndBothAreSpoken() {
        val row = receiptFixture().groups.first().rows.single().copy(
            sets = listOf(ReceiptSet(245.0, 6, 0)),
            record = RecordMark(PrKind.WEIGHT, PrKind.REPS, 0, ReceiptSet(240.0, 5, 0)),
        )
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row)))))
        rule.onNodeWithText("Weight PR", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Rep PR", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Barbell Back Squat. Top set, 245 pounds for 6 reps. Weight record and rep record, up from 240 pounds for 5 reps.").assertExists()
    }

    @Test
    fun uncheckedRecordsSaySoAndSuppressAllRecordLabels() {
        setReceipt(receiptFixture().copy(recordsChecked = false))
        rule.onNodeWithText("Workout saved. Records weren't checked.").assertExists()
        rule.onAllNodesWithText("PR", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Barbell Back Squat. Top set, 245 pounds for 5 reps.").assertExists()
    }

    @Test
    fun allPlansDoneAppearsOnlyWhenTheFrozenReceiptSaysSo() {
        val receipt = mutableStateOf(receiptFixture().let { it.copy(groups = it.groups.filter { group -> group.rows.none { row -> row.shape == ReceiptRow.Shape.SKIPPED } }) })
        rule.setContent { AppTheme { Receipt(receipt.value) } }
        rule.onNodeWithText("All plans done for today.").assertDoesNotExist()
        rule.runOnIdle { receipt.value = receipt.value.copy(allPlansDone = true) }
        rule.onNodeWithText("All plans done for today.").assertIsDisplayed()
    }

    @Test
    fun doneShareAndSystemBackReportOutwardOnceEach() {
        var finishes = 0
        var shares = 0
        setReceipt(onShare = { shares++ }, onFinish = { finishes++ })
        rule.onNodeWithContentDescription("Share").performClick()
        assertEquals(1, shares)
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, finishes)
        pressBack()
        assertEquals(2, finishes)
    }

    @Test
    fun aMixedReceiptListsTheLiftsThenTheCardioAndKeepsNoteAndShare() {
        setReceipt(receiptFixture().copy(activity = CardioActivity.ROAD_RUN, cardioRows = timeTargetFixture().cardioRows))
        rule.onNodeWithText("Squat focus, saved.").assertExists()
        val lift = rule.onNodeWithText("Barbell Back Squat", useUnmergedTree = true)
        val cardio = rule.onNodeWithText("Road run", useUnmergedTree = true)
        lift.assertExists()
        cardio.assertExists()
        assertTrue(lift.fetchSemanticsNode().positionInRoot.y < cardio.fetchSemanticsNode().positionInRoot.y)
        listOf("30:00", "Distance", "3.24 mi").forEach {
            rule.onNodeWithText(it, useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithText("Add a note").assertExists()
        rule.onNodeWithContentDescription("Share").assertExists()
    }

    @Test
    fun cardioHasNeitherShareNorNote() {
        setReceipt(cardioReceiptFixture())
        rule.onNodeWithContentDescription("Share").assertDoesNotExist()
        rule.onNodeWithText("Add a note").assertDoesNotExist()
    }

    @Test
    fun `a cardio receipt shows its note without an edit action`() {
        setReceipt(timeTargetFixture().copy(note = "Easy"))
        rule.onNodeWithText("Easy").performScrollTo().assertHasNoClickAction()
        rule.onNodeWithText("Add a note").assertDoesNotExist()
        rule.onNodeWithText("Edit note").assertDoesNotExist()
    }

    @Test
    fun cardioIntervalsShowTheFastestAndOpenToRecoveryAndRecord() {
        setReceipt(cardioReceiptFixture())
        listOf("Track run", "Warm-up", "10:00", "6 × 400 m", "Fastest", "1:38", "1:42", "Faster PR", "Cool-down", "5:00").forEach {
            rule.onNodeWithText(it, useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithContentDescription("Warm-up, 10 minutes.").assertHasNoClickAction()
        rule.onNodeWithContentDescription("Cool-down, 5 minutes.").assertHasNoClickAction()
        rule.onNodeWithContentDescription(CARDIO).assertHasClickAction().performClick()
        rule.onNodeWithText("Recovery 1:30 each", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Recovery 1 minute 30 seconds each").assertExists()
        rule.onNodeWithText("PR, up from 1:42", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Interval 2, 1 minute 38 seconds, faster record, up from 1 minute 42 seconds.").assertExists()
    }

    @Test fun measuredOverrunShowsTwoLegsAndDerivedReadingShowsTheTotal() {
        val result = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(1500)),
            CardioSetStatus.FINISHED, 1690, 5600, DistanceSource.MANUAL, metresAtEnd = 5000,
            secondsAtEnd = 1500, readingSource = ReadingSource.TYPED)
        val progress = mutableStateOf(ReceiptProgressState(cardioSets = listOf(listOf(result))))
        val receipt = timeTargetFixture().copy(cardioRows = ReceiptBuilder.cardio(CardioActivity.ROAD_RUN,
            listOf(result), emptyMap()))
        rule.setContent { AppTheme { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.KM) {
            SessionReceiptScreen(receipt, {}, {}, progress = progress.value)
        } } }
        rule.onNodeWithText("5.00 km at 25:00", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("3:10, 0.60 km past the target", useUnmergedTree = true).assertExists()
        rule.runOnIdle { progress.value = progress.value.copy(cardioSets = listOf(listOf(
            result.copy(metresAtEnd = null, readingSource = null)))) }
        rule.onNodeWithText("5.00 km at 25:00", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("3:10, 0.60 km past the target", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("5.6 km", useUnmergedTree = true).assertExists()
    }

    @Test fun mergedDistanceWarmupsExpandToShowEachSetsMeasuredLegs() {
        val spec = CardioSetSpec(CardioRole.WARMUP, Measure.Distance(500))
        val results = listOf(
            CardioSetResult(spec, CardioSetStatus.FINISHED, 165, 550, DistanceSource.MANUAL,
                secondsAtEnd = 150, readingSource = ReadingSource.TYPED),
            CardioSetResult(spec, CardioSetStatus.FINISHED, 180, 600, DistanceSource.MANUAL,
                secondsAtEnd = 160, readingSource = ReadingSource.TYPED),
        )
        val receipt = timeTargetFixture().copy(cardioRows = ReceiptBuilder.cardio(CardioActivity.ROAD_RUN,
            results, emptyMap()))
        assertEquals(2, receipt.cardioRows.single().intervals.size)
        val progress = ReceiptProgressState(cardioSets = listOf(results))
        rule.setContent { AppTheme { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.KM) {
            SessionReceiptScreen(receipt, {}, {}, progress = progress)
        } } }

        rule.onNodeWithText("2:30 at 0.5 km", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("2:40 at 0.5 km", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Warm-up, 5 minutes 45 seconds.").assertHasClickAction().performClick()
        listOf("2:30 at 0.5 km", "0:15, 0.05 km past the target",
            "2:40 at 0.5 km", "0:20, 0.10 km past the target").forEach {
            rule.onNodeWithText(it, useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithContentDescription("Warm-up, 2 minutes 45 seconds. 2:30 at 0.5 km. 0:15, 0.05 km past the target").assertExists()
        rule.onNodeWithContentDescription("Warm-up, 3 minutes. 2:40 at 0.5 km. 0:20, 0.10 km past the target").assertExists()
        rule.onNodeWithContentDescription("Interval 1", substring = true).assertDoesNotExist()
    }

    @Test fun mergedWarmupsAndCooldownsStayClosedWithoutMeasuredLegs() {
        val results = listOf(CardioRole.WARMUP, CardioRole.COOLDOWN).flatMap { role ->
            List(2) { CardioSetResult(CardioSetSpec(role, Measure.Time(300)),
                CardioSetStatus.FINISHED, 310, 1050, DistanceSource.MANUAL,
                metresAtEnd = 1000, secondsAtEnd = 300) }
        }
        val receipt = timeTargetFixture().copy(cardioRows = ReceiptBuilder.cardio(CardioActivity.ROAD_RUN,
            results, emptyMap()))
        val progress = ReceiptProgressState(cardioSets = ReceiptBuilder.cardioGroups(results).map { it.sets })
        rule.setContent { AppTheme { SessionReceiptScreen(receipt, {}, {}, progress = progress) } }

        rule.onNodeWithContentDescription("Warm-up, 10 minutes 20 seconds.").assertHasNoClickAction()
        rule.onNodeWithContentDescription("Cool-down, 10 minutes 20 seconds.").assertHasNoClickAction()
    }

    @Test fun mergedCooldownWithOneMeasuredLegOpensAndNamesItsRole() {
        val spec = CardioSetSpec(CardioRole.COOLDOWN, Measure.Distance(500))
        val results = listOf(
            CardioSetResult(spec, CardioSetStatus.FINISHED, 150, 500, DistanceSource.MANUAL),
            CardioSetResult(spec, CardioSetStatus.FINISHED, 180, 600, DistanceSource.MANUAL,
                secondsAtEnd = 160, readingSource = ReadingSource.TYPED),
        )
        val receipt = timeTargetFixture().copy(activity = CardioActivity.TRACK_RUN,
            cardioRows = ReceiptBuilder.cardio(CardioActivity.TRACK_RUN, results, emptyMap()))
        val progress = ReceiptProgressState(cardioSets = listOf(results))
        rule.setContent { AppTheme { SessionReceiptScreen(receipt, {}, {}, progress = progress) } }

        rule.onNodeWithContentDescription("Cool-down, 5 minutes 30 seconds.").assertHasClickAction().performClick()
        rule.onNodeWithContentDescription("Cool-down, 2 minutes 30 seconds.").assertExists()
        rule.onNodeWithContentDescription("Cool-down, 3 minutes. 2:40 at 500 m. 0:20, 100 m past the target").assertExists()
        rule.onNodeWithContentDescription("Interval 1", substring = true).assertDoesNotExist()
    }

    @Test fun mixedWarmupChildrenUseTheirOwnTargetTypeForVisibleAndSpokenValues() {
        val results = listOf(
            CardioSetResult(CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
                CardioSetStatus.FINISHED, 300, 1000, DistanceSource.MANUAL),
            CardioSetResult(CardioSetSpec(CardioRole.WARMUP, Measure.Distance(500)),
                CardioSetStatus.FINISHED, 165, 550, DistanceSource.MANUAL,
                secondsAtEnd = 150, readingSource = ReadingSource.TYPED),
        )
        val receipt = timeTargetFixture().copy(activity = CardioActivity.TRACK_RUN,
            cardioRows = ReceiptBuilder.cardio(CardioActivity.TRACK_RUN, results, emptyMap()))
        val progress = ReceiptProgressState(cardioSets = listOf(results))
        rule.setContent { AppTheme { SessionReceiptScreen(receipt, {}, {}, progress = progress) } }

        rule.onNodeWithContentDescription("Warm-up, 7 minutes 45 seconds.").performClick()
        rule.onNodeWithText("1,000 m", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("2:45", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Warm-up, 1,000 meters.").assertExists()
        rule.onNodeWithContentDescription("Warm-up, 2 minutes 45 seconds. 2:30 at 500 m. 0:15, 50 m past the target").assertExists()
    }

    @Test
    fun cardioTimeTargetShowsDistanceAndFartherRecord() {
        setReceipt(timeTargetFixture())
        listOf("30:00", "Distance", "3.24 mi", "3.09 mi", "Farther PR").forEach {
            rule.onNodeWithText(it, useUnmergedTree = true).assertExists()
        }
        // #531: the row now speaks its derived pace.
        rule.onNodeWithContentDescription("30 minutes. Distance, 3.24 miles. Farther record, up from 3.09 miles. Pace 9 minutes 16 seconds per mile.").assertHasNoClickAction()
    }

    @Test fun roadReceiptReadsAndSpeaksKilometers() {
        val base = timeTargetFixture()
        val row = base.cardioRows.single().copy(intervals = listOf(CardioInterval(CardioSetStatus.FINISHED, 1800, 5000)), record = null)
        setReceipt(base.copy(cardioRows = listOf(row)), distanceUnit = DistanceUnit.KM)
        rule.onNodeWithText("5 km", useUnmergedTree = true).assertExists()
        // #531: the row now speaks its derived pace.
        rule.onNodeWithContentDescription("30 minutes. Distance, 5 kilometers. Pace 6 minutes per kilometer.").assertHasNoClickAction()
    }

    @Test fun rowerReceiptKeepsMetresUnderKilometers() {
        val base = timeTargetFixture()
        val row = base.cardioRows.single().copy(intervals = listOf(CardioInterval(CardioSetStatus.FINISHED, 1800, 2000)), record = null)
        setReceipt(base.copy(activity = CardioActivity.ROWER, cardioRows = listOf(row)), distanceUnit = DistanceUnit.KM)
        rule.onNodeWithText("2,000 m", useUnmergedTree = true).assertExists()
        // #531: the row now speaks its derived pace.
        rule.onNodeWithContentDescription("30 minutes. Distance, 2,000 meters. Pace 7 minutes 30 seconds per 500 meters.").assertHasNoClickAction()
    }

    @Test
    fun aLoneTimedRunNamesItsActualTime() {
        val base = timeTargetFixture()
        val row = base.cardioRows.single()
        setReceipt(base.copy(cardioRows = listOf(row.copy(
            intervals = listOf(row.intervals.single().copy(elapsedSeconds = 1690)),
        ))))
        rule.onNodeWithText("28:10", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("30:00", useUnmergedTree = true).assertDoesNotExist()
        // #531: the row now speaks its derived pace.
        rule.onNodeWithContentDescription("28 minutes 10 seconds. Distance, 3.24 miles. Farther record, up from 3.09 miles. Pace 8 minutes 42 seconds per mile.")
            .assertHasNoClickAction()
    }

    @Test
    fun blankCardioDistanceIsNeverZeroAndHasNoRecord() {
        val base = timeTargetFixture()
        val row = base.cardioRows.single()
        setReceipt(base.copy(cardioRows = listOf(row.copy(intervals = listOf(row.intervals.single().copy(metres = null)), record = null))))
        rule.onNodeWithText("Distance not entered", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("0 mi", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Farther PR", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("30 minutes. Distance not entered.").assertExists()
    }

    @Test
    fun missingTopSetShowsHeaviestWithoutOpening() {
        val row = receiptFixture().groups.first().rows.single().copy(name = "Romanian Deadlift",
            shape = ReceiptRow.Shape.HEAVIEST_NO_TOP, sets = listOf(ReceiptSet(210.0, 3, 0)),
            lines = listOf(ValueLine(210.0, listOf(0))), record = null)
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row)))))
        listOf("Heaviest", "210 lb × 3", "top set not logged").forEach { rule.onNodeWithText(it, useUnmergedTree = true).assertExists() }
        rule.onNodeWithContentDescription("Romanian Deadlift. Top set not logged. Heaviest set, 210 pounds for 3 reps.").assertHasNoClickAction()
    }

    @Test
    fun addedWeightAndLoadedHoldsUseTheExistingFormatters() {
        val dip = ReceiptRow("Weighted Dip", TrackingType.WEIGHTED, ReceiptRow.Shape.STRAIGHT,
            listOf(8, 8, 6).map { ReceiptSet(25.0, it, 0) }, listOf(ValueLine(25.0, listOf(0, 1, 2))), 0, null, null)
        val hold = ReceiptRow("Loaded Plank", TrackingType.TIMED, ReceiptRow.Shape.STRAIGHT,
            listOf(ReceiptSet(25.0, 0, 90)), listOf(ValueLine(null, listOf(0))), 0, null,
            RecordMark(PrKind.HOLD, PrKind.WEIGHT, 0, ReceiptSet(0.0, 0, 75)))
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(dip)), ReceiptGroup(null, listOf(hold)))))
        rule.onNodeWithText("25 lb × 8, 8, 6", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("90 s at 25 lb", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Loaded Plank. Holds of 1 minute 30 seconds with 25 pounds added. Hold record and weight record on set 1, up from 1 minute 15 seconds.").assertExists()
    }

    @Test
    fun mixedLegacyTrackingAppearsOnlyInTheOpenSetLines() {
        val row = ReceiptRow("Plank", TrackingType.TIMED, ReceiptRow.Shape.STRAIGHT,
            listOf(ReceiptSet(0.0, 60, 0), ReceiptSet(0.0, 0, 60)), listOf(ValueLine(null, listOf(1))),
            0, PlannedSets(2, 0.0, 0, 60), null)
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row)))))
        rule.onNodeWithText("60 s", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Plank. Holds of 1 minute.").performClick()
        rule.onNodeWithContentDescription("Set 1, 60 reps").assertExists()
        rule.onNodeWithContentDescription("Set 2, 1 minute").assertExists()
        rule.onNodeWithText("Planned 2 × 60s", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Planned 2 holds of 1 minute").assertExists()
    }

    @Test
    fun shortenedAndSkippedIntervalsKeepTheirStatusAndVariableRecovery() {
        val row = CardioReceiptRow(CardioRole.WORK, 4, null, 400,
            listOf(CardioInterval(CardioSetStatus.FINISHED, 101, 400), CardioInterval(CardioSetStatus.FINISHED, 98, 400),
                CardioInterval(CardioSetStatus.SHORTENED, 80, 300), CardioInterval(CardioSetStatus.SKIPPED, 0, null)),
            listOf(90, 120, 90), CardioRecordMark(PrKind.FASTER, 1, 102, null))
        setReceipt(cardioReceiptFixture().copy(cardioRows = listOf(row)))
        // #531: measured shortened work contributes to the row's average pace.
        rule.onNodeWithContentDescription("4 by 400 meters. Fastest, 1 minute 38 seconds. Faster record, up from 1 minute 42 seconds. Pace 6 minutes 48 seconds per mile.").performClick()
        rule.onNodeWithText("Shortened: 300 m of 400 m", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Set 3. Shortened: 300 meters of 400 meters.").assertExists()
        rule.onNodeWithContentDescription("Interval 4, skipped.").assertExists()
        rule.onNodeWithText("Recovery 1:30, 2:00, 1:30", useUnmergedTree = true).assertExists()
    }

    @Test
    fun entirelySkippedWorkStillOpensToItsIntervals() {
        val row = cardioReceiptFixture().cardioRows[1].copy(intervals = List(6) { CardioInterval(CardioSetStatus.SKIPPED, 0, null) }, record = null)
        setReceipt(cardioReceiptFixture().copy(cardioRows = listOf(row)))
        rule.onNodeWithText("Skipped", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("6 × 400 m, skipped.").assertHasClickAction().performClick()
        rule.onNodeWithContentDescription("Interval 1, skipped.").assertExists()
    }

    @Test
    fun recordValuesAndSpeechStayPresentDuringRollAndRestoreWithoutAnotherHaptic() {
        val restoration = StateRestorationTester(rule)
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                feedback += hapticFeedbackType
            }
        }
        restoration.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) { AppTheme { Receipt(receiptFixture()) } }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("245 lb × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription(SQUAT).assertExists()
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("245 lb × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription(SQUAT).assertExists()
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("245 lb × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("240 lb × 5", useUnmergedTree = true).assertExists()
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
    }

    @Test
    fun reducedMotionShowsFinalValuesImmediately() {
        setAnimatorScale(0f)
        setReceipt()
        rule.onNodeWithText("245 lb × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("240 lb × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription(SQUAT).assertExists()
    }

    @Test
    fun openRowsSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { AppTheme { Receipt(receiptFixture()) } }
        rule.onNodeWithContentDescription(CURL).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Planned 3 × 10 at 35 lb", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription(CURL).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test
    fun largeFontKeepsDonePinnedAndTheRecordLabelReadable() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme { Receipt(receiptFixture()) }
            }
        }
        rule.onNodeWithText("Weight PR", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun finishingDelaysSevenGhostsDisablesActionsAndSwallowsBack() {
        rule.mainClock.autoAdvance = false
        var backs = 0
        rule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            androidx.activity.compose.BackHandler { backs++ }
            AppTheme { FinishingScreen(attemptFixture(), finishingFixture()) }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onAllNodesWithTag("ghost", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText("Done").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Share").assertIsNotEnabled()
        rule.onNodeWithText("Saving your workout…").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithText("Saving your workout…").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onAllNodesWithTag("ghost", useUnmergedTree = true).assertCountEquals(7)
        rule.onNodeWithText("Superset, 3 rounds").assertExists()
        pressBack()
        assertEquals(0, backs)
    }

    @Test
    fun finishingLoadingStateShowsSavingUnderTheHeader() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { FinishingScreen(attemptFixture(), DayUiState(loading = true)) } }
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithText("Squat focus").assertExists()
        rule.onNodeWithText("Saving your workout…").assertIsDisplayed()
        rule.onAllNodesWithTag("ghost", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun finishingShowsSkippedLiftsAfterTheReveal() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme { FinishingScreen(attemptFixture(), DayUiState(skippedLifts = listOf(SkippedLift(1, 0, "Face Pull")))) }
        }
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithContentDescription("Face Pull, skipped.").assertHasNoClickAction()
    }

    @Test
    fun failedFinishOffersRetryAndBothWaysBackWithoutClaimingDone() {
        var retries = 0
        var backs = 0
        rule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            AppTheme { FinishFailedScreen(attemptFixture().copy(failed = true), { retries++ }, { backs++ }) }
        }
        listOf("Squat focus", "Couldn't finish the workout.",
            "Every set you logged is saved. Try again, or go back to the workout.").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Squat focus, saved.").assertDoesNotExist()
        val retry = rule.onNodeWithText("Try again")
        val back = rule.onNodeWithText("Back to workout")
        assertTrue(abs(retry.fetchSemanticsNode().boundsInRoot.width - back.fetchSemanticsNode().boundsInRoot.width) < 1f)
        retry.performClick()
        assertEquals(1, retries)
        rule.onNodeWithText("Back to workout").performClick()
        assertEquals(1, backs)
        pressBack()
        assertEquals(2, backs)
    }

    @Test
    fun noteSaveWaitsForAResultAndRetriesInsideTheSheet() {
        val save = mutableStateOf<NoteSave?>(null)
        val saved = mutableListOf<String>()
        var dismissals = 0
        rule.setContent { AppTheme { NoteSheet("x", { saved += it }, save = save.value, onDismiss = { dismissals++ }) } }
        rule.onNodeWithText("Save note").performClick()
        assertEquals(listOf("x"), saved)
        assertEquals(0, dismissals)
        rule.runOnIdle { save.value = NoteSave.Failed("x") }
        rule.onNode(hasText("Note not saved.") and hasAnyAncestor(isDialog())).assertExists()
        rule.onNodeWithText("Save note").assertDoesNotExist()
        rule.onAllNodesWithText("Try again").assertCountEquals(1)
        rule.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("x", "x"), saved)
        assertEquals(0, dismissals)
        rule.runOnIdle { save.value = NoteSave.Saved("x") }
        rule.waitForIdle()
        assertEquals(1, dismissals)
    }

    @Test
    fun defaultNoteSheetStillClosesOnSave() {
        var saved = ""
        var dismissals = 0
        rule.setContent { AppTheme { NoteSheet("", { saved = it }) { dismissals++ } } }
        rule.onNodeWithText("Note").performTextReplacement("Felt steady")
        rule.onNodeWithText("Save note").performClick()
        assertEquals("Felt steady", saved)
        assertEquals(1, dismissals)
    }

    @Test
    fun receiptNoteWaitsForSaveAndClearsTheResultWhenClosed() {
        val save = mutableStateOf<NoteSave?>(null)
        val saved = mutableListOf<String>()
        var handled = 0
        rule.setContent {
            AppTheme {
                SessionReceiptScreen(receiptFixture(), {}, {}, onSetNote = { saved += it }, noteSave = save.value,
                    onNoteSaveHandled = { handled++ })
            }
        }
        rule.onNodeWithText("Add a note").performScrollTo().performClick()
        rule.onNodeWithText("Note").performTextReplacement("Felt steady")
        rule.onNodeWithText("Save note").performClick()
        assertEquals(listOf("Felt steady"), saved)
        rule.onNodeWithText("Note").assertExists()
        rule.runOnIdle { save.value = NoteSave.Saved("Felt steady") }
        rule.onNodeWithText("Note").assertDoesNotExist()
        assertEquals(1, handled)
    }

    @Test
    fun failedReceiptNoteSaveKeepsTheDraftAfterDismissAndReopen() {
        val save = mutableStateOf<NoteSave?>(null)
        val saved = mutableListOf<String>()
        var handled = 0
        rule.setContent {
            AppTheme {
                SessionReceiptScreen(receiptFixture(), {}, {}, onSetNote = { saved += it }, noteSave = save.value,
                    onNoteSaveHandled = { handled++; save.value = null })
            }
        }
        rule.onNodeWithText("Add a note").performScrollTo().performClick()
        rule.onNodeWithText("Note").performTextReplacement("Felt steady")
        rule.onNodeWithText("Save note").performClick()
        rule.runOnIdle { save.value = NoteSave.Failed("Felt steady") }
        rule.onNodeWithText("Note not saved.").assertIsDisplayed()
        // Keep edits made after the failed write too, rather than reconstructing from the result.
        val draft = "Felt steady, last set slow"
        rule.onNodeWithText("Note").performTextReplacement(draft)
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        rule.onNodeWithText("Note").assertDoesNotExist()
        rule.runOnIdle { assertNull(save.value); assertEquals(1, handled) }

        rule.onNodeWithText("Add a note").performScrollTo().performClick()
        rule.onNodeWithText("Note").assertTextContains(draft)
        rule.onNodeWithText("Note not saved.").assertDoesNotExist()
        rule.onNodeWithText("Save note").performClick()
        rule.runOnIdle { assertEquals(listOf("Felt steady", draft), saved) }
    }

    @Test
    fun aNoteResultArrivingAfterTheSheetClosedNeverReachesTheNextSheet() {
        val save = mutableStateOf<NoteSave?>(null)
        var handled = 0
        rule.setContent {
            AppTheme {
                SessionReceiptScreen(receiptFixture(), {}, {}, onSetNote = {}, noteSave = save.value,
                    onNoteSaveHandled = { handled++; save.value = null })
            }
        }
        // The sheet is closed when the late result lands.
        rule.runOnIdle { save.value = NoteSave.Saved("Felt steady") }
        rule.runOnIdle { assertNull(save.value); assertEquals(1, handled) }
        rule.onNodeWithText("Add a note").performScrollTo().performClick()
        rule.onNodeWithText("Note").assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun theReceiptCoversTheWindowButItsContentKeepsTheReadableCap() {
        rule.setContent { AppTheme { Receipt(receiptFixture(), Modifier.testTag("receipt")) } }
        val root = rule.onRoot().fetchSemanticsNode().size
        assertEquals("the receipt must cover the whole window", root, rule.onNodeWithTag("receipt").fetchSemanticsNode().size)
        val readable = with(rule.density) { ReadableWidth.toPx() }
        val headline = rule.onNodeWithText("Squat focus, saved.").fetchSemanticsNode().boundsInRoot
        val back = rule.onNodeWithText("Done").fetchSemanticsNode().boundsInRoot
        assertTrue("the ledger is ${back.right - headline.left}px wide, past the $readable px cap", back.right - headline.left <= readable)
        assertTrue("the ledger drifted off center: ${headline.left}px left, ${root.width - back.right}px right",
            abs(headline.left - (root.width - back.right)) <= 2f)
    }

    @Test fun `receipt Undo and Why share one block and Rough day disables after Undo`() {
        val receipt = receiptFixture().copy(groups = receiptFixture().groups.take(1), cardioRows = emptyList(), activity = null)
        val line = ReceiptLiftLine(190.0, null, null, 5.0, false, "ALL_MADE", 1, true)
        val initial = ReceiptProgressState(lifts = listOf(ReceiptLiftProgress(ReceiptSlotRef(0, 0, 7, "main"), "Bench Press", line, whyLine = line)),
            undoVisible = true, roughEnabled = true)
        val state = mutableStateOf(initial)
        val mutation = mutableStateOf<ReceiptMutation?>(null)
        rule.setContent {
            AppTheme {
                SessionReceiptScreen(receipt, {}, {}, progress = state.value, mutation = mutation.value,
                    onMutationHandled = { mutation.value = null },
                    onUndo = {
                        state.value = initial.copy(undoVisible = false, roughEnabled = false,
                            lifts = initial.lifts.map { it.copy(line = it.line.copy(offered = true), whyLine = it.whyLine?.copy(offered = true)) })
                        mutation.value = ReceiptMutation.Undone
                    })
            }
        }
        rule.onNodeWithText("Undo changes").performScrollTo().performClick()
        rule.onNodeWithText("Undo changes").assertDoesNotExist()
        rule.onNodeWithText("Changes undone").assertIsDisplayed()
        rule.onNodeWithText("Next time: 190 lb offered (+5)", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Rough day").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Why").performScrollTo().performClick()
        rule.onNodeWithText("Bench Press 190 lb offered (+5): all sets made.").assertExists()
    }

    @Test fun `a refused Rough day toggle has its own Snackbar wording`() {
        val mutation = mutableStateOf<ReceiptMutation?>(ReceiptMutation.RoughDayOpen("A"))
        rule.setContent {
            AppTheme { SessionReceiptScreen(receiptFixture(), {}, {}, mutation = mutation.value) }
        }
        rule.onNodeWithText("Rough day isn't available while day A is open.").assertIsDisplayed()
        rule.runOnIdle { mutation.value = ReceiptMutation.RoughDayFailed }
        rule.onNodeWithText("Couldn't save Rough day.").assertIsDisplayed()
    }

    @Test fun `cardio How was it has three choices and a second tap clears Good`() {
        val selected = mutableStateOf<cloud.trotter.log.strength.domain.progression.CardioEffort?>(null)
        rule.setContent {
            AppTheme {
                cloud.trotter.log.strength.ui.day.CardioEffortRow(selected.value, { selected.value = it }, "receipt-cardio-effort")
            }
        }
        rule.onNodeWithText("How was it?").assertExists()
        rule.onNodeWithTag("receipt-cardio-effort-GOOD").performClick()
        rule.runOnIdle { assertEquals(cloud.trotter.log.strength.domain.progression.CardioEffort.GOOD, selected.value) }
        rule.onNodeWithTag("receipt-cardio-effort-GOOD").performClick()
        rule.runOnIdle { assertNull(selected.value) }
        rule.onNodeWithText("Max").assertDoesNotExist()
    }

    @Test fun `a failed cardio effort write has save failure wording`() {
        rule.setContent {
            AppTheme { SessionReceiptScreen(receiptFixture(), {}, {}, mutation = ReceiptMutation.EffortFailed) }
        }
        rule.onNodeWithText("Couldn't save.").assertIsDisplayed()
        rule.onNodeWithText("Couldn't undo.").assertDoesNotExist()
    }

    // PR 7b: the ladder note includes the Hard level drop.
    private val ladderNote = "Easy counts toward the next level. Two Easy runs finish a level; Good or no answer repeats it; Hard drops a level."
    private val weekCap = "Up to three Easy runs a week count toward the next level."
    private fun runProgress(change: CardioChange = CardioChange.Repeat, from: Int? = 1, hold: LadderHold? = null,
                            credit: Boolean = false, held: Boolean = false, block: CardioBlock = RunWalkLadder.block(1, CardioActivity.ROAD_RUN)) =
        ReceiptProgressState(cardioSessionId = 1, cardioStored = block, runLadder = from != null,
            run = RunResult(change, from, hold, credit, held, block))

    @Test
    @Config(fontScale = 2f)
    fun largeFontRunReceiptShowsTheLadderNoteAndReplanOffer() {
        var accepted = 0
        rule.setContent { AppTheme {
            SessionReceiptScreen(timeTargetFixture(), {}, {},
                progress = runProgress().copy(replan = ReceiptReplan(RunPhase.BASE, true)),
                onAcceptReplan = { accepted++ })
        } }
        rule.onNodeWithText(ladderNote).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Ready for Base.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Move to Base").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(1, accepted) }
    }

    @Test
    @Config(fontScale = 2f)
    fun largeFontRunWhySheetLines() {
        rule.setContent { AppTheme {
            SessionReceiptScreen(timeTargetFixture(), {}, {}, progress = runProgress(hold = LadderHold.WEEK_CAP))
        } }
        rule.onNodeWithText("Why").performScrollTo().performClick()
        rule.onNodeWithText(weekCap).performScrollTo().assertIsDisplayed()
    }

    @Test fun `run receipt values and Why lines read the stored result`() {
        val state = mutableStateOf(runProgress(credit = true))
        rule.setContent { AppTheme {
            androidx.compose.foundation.layout.Column {
                Text(cardioNextLine(state.value).orEmpty())
                Text(cardioWhyLine(state.value, "Run").orEmpty())
                Text(spokenCardioNextLine(state.value).orEmpty())
            }
        } }
        fun expect(progress: ReceiptProgressState, next: String, why: String?) {
            rule.runOnIdle { state.value = progress }
            rule.onNodeWithText("Next time: $next").assertIsDisplayed()
            if (why != null) rule.onNodeWithText(why).assertIsDisplayed()
        }
        // PR 7b: counted runs finish a level without promising the next run moves up.
        expect(runProgress(credit = true), "level 1 of 12", "Level 1: one Easy run counted. Two counted Easy runs finish a level.")
        expect(runProgress(hold = LadderHold.WEEK_CAP), "level 1 of 12", weekCap)
        expect(runProgress(hold = LadderHold.BUSY), "level 1 of 12", "A busy two weeks holds the next level for a week.")
        expect(runProgress(hold = LadderHold.BACKDATED), "level 1 of 12",
            "An Easy run dated before the last counted one doesn't count toward the next level.")
        expect(runProgress(CardioChange.LevelTo(2), held = true), "level 2 of 12 offered", "Level 1: two Easy runs counted.")
        expect(runProgress(CardioChange.LevelTo(2), from = 3), "level 2 of 12", "Back to level 2.")
        val continuous = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1800))))
        expect(runProgress(CardioChange.LadderDone, from = 12, block = continuous), "30:00", "Run/walk ladder done: 30 minutes running.")
        val intervals = continuous.copy(sets = List(5) { CardioSetSpec(CardioRole.WORK, Measure.Time(180)) })
        expect(runProgress(CardioChange.Step, from = null, block = intervals), "5 × 3:00", "Run 5 × 3:00 next time: two sessions finished.")
        expect(runProgress(CardioChange.Alternate(RunSession.VO2MAX), from = null, block = intervals),
            "Intervals, 5 × 3:00", "Threshold and Intervals take turns on this day.")
        val increased = continuous.copy(sets = listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1890))))
        val offer = CardioNextTime.Offer(increased, continuous, true, CardioIncrease.Time(90), 1)
        for (capped in listOf(false, true)) {
            expect(runProgress(CardioChange.Increase(cloud.trotter.log.strength.domain.model.ProgressMeasure.TIME, capped),
                from = null, block = increased).copy(cardioNext = offer), "31:30",
                if (capped) "Run 31:30 next time (+1:30): held to 10% past your longest recent run."
                else "Run 31:30 next time (+1:30): every set finished.")
        }
        expect(runProgress(), "level 1 of 12", null)
        rule.onNodeWithText(weekCap).assertDoesNotExist()
    }

    @Test fun `re-plan applied and offered lines use the phase or full plan`() {
        val state = mutableStateOf(ReceiptProgressState(replan = ReceiptReplan(RunPhase.BASE, false)))
        rule.setContent { AppTheme { SessionReceiptScreen(timeTargetFixture(), {}, {}, progress = state.value) } }
        rule.onNodeWithText("Base from the next workout.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(ladderNote).assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(replan = ReceiptReplan(null, false)) }
        rule.onNodeWithText("Run/walk ladder done. The full plan starts with the next workout.").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(replan = ReceiptReplan(null, true)) }
        rule.onNodeWithText("Ready for the full plan.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Start the full plan").performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    @Test fun `run receipt mutations show their messages`() {
        val mutation = mutableStateOf<ReceiptMutation>(ReceiptMutation.UndoneLadderKept)
        rule.setContent { AppTheme { SessionReceiptScreen(receiptFixture(), {}, {}, mutation = mutation.value) } }
        rule.onNodeWithText("Changes undone, except the ladder level: a ladder day changed since.").assertIsDisplayed()
        rule.runOnIdle { mutation.value = ReceiptMutation.ReplanFailed }
        rule.onNodeWithText("The plan can't move up yet.").assertIsDisplayed()
    }

    @Test fun `re-plan mutations name the cause`() {
        val mutation = mutableStateOf<ReceiptMutation>(ReceiptMutation.ReplanRunOpen)
        rule.setContent { AppTheme { SessionReceiptScreen(receiptFixture(), {}, {}, mutation = mutation.value) } }
        rule.onNodeWithText("Not moved: a workout is open.").assertIsDisplayed()
        rule.runOnIdle { mutation.value = ReceiptMutation.ReplanGone }
        rule.onNodeWithText("This offer ended with a later workout.").assertIsDisplayed()
        rule.runOnIdle { mutation.value = ReceiptMutation.UndoneReplanKept }
        rule.onNodeWithText("Changes undone, except the new phase: a workout is open on a day it added.").assertIsDisplayed()
    }

    private fun setReceipt(
        receipt: WorkoutReceipt = receiptFixture(),
        onShare: () -> Unit = {},
        onFinish: () -> Unit = {},
        setCounts: ReceiptSetCounts? = null,
        distanceUnit: DistanceUnit = DistanceUnit.MI,
        nextTimes: ReceiptNextTimes? = null,
        backoffLabels: ReceiptBackoffLabels? = null,
    ) {
        rule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalDistanceUnit provides distanceUnit) {
                AppTheme { SessionReceiptScreen(receipt, onShare, onFinish, setCounts = setCounts, backoffLabels = backoffLabels,
                    progress = ReceiptProgressState(lifts = nextTimes?.takeIf { it.sessionId == receipt.sessionId }?.rows.orEmpty().map { next ->
                        val line = ReceiptLiftLine(next.offerLb ?: next.openingLb, null, null,
                            next.offerLb?.let { it - next.openingLb }, false, "ALL_MADE", 1, next.offerLb != null)
                        ReceiptLiftProgress(ReceiptSlotRef(next.groupIndex, next.rowIndex, 1, "main"), "", line, whyLine = line)
                    })) }
            }
        }
    }

    @Composable
    private fun Receipt(receipt: WorkoutReceipt, modifier: Modifier = Modifier) {
        SessionReceiptScreen(receipt, {}, {}, modifier = modifier)
    }

    private fun pressBack() {
        rule.runOnUiThread { requireNotNull(backDispatcher).onBackPressed() }
        rule.waitForIdle()
    }

    private companion object {
        const val SQUAT = "Barbell Back Squat. Top set, 245 pounds for 5 reps. Weight record, up from 240 pounds for 5 reps."
        const val CURL = "Dumbbell Curl. 35 pounds: 10 and 10 reps. 30 pounds: 8 reps. Rep record on set 1, up from 35 pounds for 9 reps."
        // #531: track pace uses the MI setting while its distance stays in meters.
        const val CARDIO = "6 by 400 meters. Fastest, 1 minute 38 seconds. Faster record, up from 1 minute 42 seconds. Pace 6 minutes 47 seconds per mile."
    }
    @Test fun `a lower next time reads with a minus sign`() {
        val bench = ReceiptRow("Bench", TrackingType.WEIGHTED, ReceiptRow.Shape.STRAIGHT,
            List(3) { ReceiptSet(185.0, 5, 0) }, listOf(ValueLine(185.0, listOf(0, 1, 2))),
            0, PlannedSets(3, 185.0, 5, 0), null)
        val receipt = receiptFixture().copy(groups = listOf(ReceiptGroup(3, listOf(bench))))
        setReceipt(receipt, nextTimes = ReceiptNextTimes(receipt.sessionId, listOf(ReceiptNextTime(0, 0, 175.0, 185.0))))
        rule.onNodeWithText("Next time: 175 lb (−10)", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Bench. 185 pounds: 5, 5 and 5 reps. Next time, 175 pounds, down 10 pounds.").assertExists()
    }

    @Test fun `a sprint receipt shows Next time and the first matching Why`() {
        val progress = mutableStateOf(sprintReceiptProgressFixture())
        rule.setContent { AppTheme {
            androidx.compose.foundation.layout.Column {
                Text(cardioNextLine(progress.value).orEmpty())
                Text(cardioWhyLine(progress.value, "Sprint").orEmpty())
                Text(spokenCardioNextLine(progress.value).orEmpty())
            }
        } }
        rule.onNodeWithText("Next time: 8 × 30 m").assertIsDisplayed()
        fun expect(next: ReceiptProgressState, why: String?) {
            rule.runOnIdle { progress.value = next }
            val value = when {
                next.run?.introLeft != null -> "4 × 10 m"
                next.run?.change == CardioChange.Step -> "8 × 30 m"
                else -> "8 × 20 m"
            }
            rule.onNodeWithText("Next time: $value").assertIsDisplayed()
            if (why != null) rule.onNodeWithText(why).assertIsDisplayed()
        }
        expect(sprintReceiptProgressFixture(CardioChange.Restart, SprintDose.TRIAL),
            "Back to the starting dose after more than two weeks without sprinting.")
        expect(sprintReceiptProgressFixture(dose = SprintDose.TRIAL), "Time trial: no step from this session.")
        expect(sprintReceiptProgressFixture(dose = SprintDose.SHORTENED), "Shortened for the weekly limit: no step from this session.")
        expect(sprintReceiptProgressFixture(dose = SprintDose.SUBSTITUTE), "Tempo in place of sprints for the weekly limit: no step from this session.")
        expect(sprintReceiptProgressFixture(dose = SprintDose.TEMPO), "Tempo after the time trial: no step from this session.")
        expect(sprintReceiptProgressFixture(introLeft = 0), "Two full sessions at this dose: one step more next time.")
        expect(sprintReceiptProgressFixture(CardioChange.Repeat, introLeft = 0, credit = true), "Starting dose done: steps begin next time.")
        expect(sprintReceiptProgressFixture(CardioChange.Repeat, introLeft = 1), "Starting dose: 1 more session.")
        expect(sprintReceiptProgressFixture(CardioChange.Repeat, introLeft = 2, credit = true), "Starting dose: 2 more sessions.")
        expect(sprintReceiptProgressFixture(CardioChange.Repeat, credit = true), "One full session at this dose. The next one adds a step.")
        expect(sprintReceiptProgressFixture(CardioChange.Repeat), null)
        rule.onNodeWithText("One full session at this dose. The next one adds a step.").assertDoesNotExist()
    }

    @Test @Config(fontScale = 2f)
    fun largeFontSprintReceipt() {
        rule.setContent { AppTheme {
            SessionReceiptScreen(sprintReceiptFixture(), {}, {}, progress = sprintReceiptProgressFixture())
        } }
        rule.onNodeWithContentDescription("Next time, 8 × 30 m.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Why").performScrollTo().performClick()
        rule.onNodeWithText("Two full sessions at this dose: one step more next time.").performScrollTo().assertIsDisplayed()
    }

    @Test fun `a sprint intro receipt exposes its Why`() {
        rule.setContent { AppTheme {
            SessionReceiptScreen(sprintReceiptFixture(intro = true), {}, {},
                progress = sprintReceiptProgressFixture(CardioChange.Repeat, introLeft = 2))
        } }
        rule.onNodeWithText("Why").performScrollTo().performClick()
        rule.onNodeWithText("Starting dose: 2 more sessions.").performScrollTo().assertIsDisplayed()
    }

    @Test fun `an each side set line reads left then right`() {
        val row = ReceiptRow("Side Plank", TrackingType.TIMED, ReceiptRow.Shape.STRAIGHT,
            listOf(ReceiptSet(0.0, 0, 40)), listOf(ValueLine(null, listOf(0))), 0, null, null)
        val sides = ReceiptSideSet(0, 0, 0, ReceiptSet(0.0, 0, 40), ReceiptSet(0.0, 0, 52))
        val receipt = receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row))))
        rule.setContent { AppTheme { SessionReceiptScreen(receipt, {}, {},
            sides = ReceiptSides(receipt.sessionId, listOf(sides), emptyList())) } }
        rule.onNodeWithContentDescription("Side Plank. Holds of 40 seconds.").performScrollTo().performClick()
        rule.onNodeWithText("L 40 s · R 52 s", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Set 1, left 40 seconds, right 52 seconds").assertExists()
    }

    @Test fun `a repeated gap is named once in neutral words`() {
        val gap = mutableStateOf<ReceiptSideGap?>(ReceiptSideGap(0, 0, true))
        val row = ReceiptRow("Side Plank", TrackingType.TIMED, ReceiptRow.Shape.STRAIGHT,
            listOf(ReceiptSet(0.0, 0, 40)), listOf(ValueLine(null, listOf(0))), 0, null, null)
        rule.setContent { AppTheme { StrengthReceiptRow(row, WeightUnit.LB, 0, "side-gap", { 0f }, gap = gap.value) } }
        rule.onAllNodesWithText("Left side lower the last 3 sessions", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithContentDescription("Left side lower the last 3 sessions", substring = true).assertExists()
        rule.runOnIdle { gap.value = null }
        rule.onNodeWithText("Left side lower the last 3 sessions", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `receipt sides from another session do not label the shown receipt`() {
        val row = ReceiptRow("Side Plank", TrackingType.TIMED, ReceiptRow.Shape.STRAIGHT,
            listOf(ReceiptSet(0.0, 0, 40)), listOf(ValueLine(null, listOf(0))), 0, null, null)
        val receipt = receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row))))
        rule.setContent { AppTheme { SessionReceiptScreen(receipt, {}, {}, sides = ReceiptSides(receipt.sessionId + 1,
            listOf(ReceiptSideSet(0, 0, 0, ReceiptSet(0.0, 0, 40), ReceiptSet(0.0, 0, 52))),
            listOf(ReceiptSideGap(0, 0, true)))) } }
        rule.onNodeWithContentDescription("Side Plank. Holds of 40 seconds.").performScrollTo().performClick()
        rule.onNodeWithText("L 40 s · R 52 s", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Left side lower the last 3 sessions", useUnmergedTree = true).assertDoesNotExist()
    }

}

