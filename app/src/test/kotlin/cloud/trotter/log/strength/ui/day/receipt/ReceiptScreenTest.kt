package cloud.trotter.log.strength.ui.day.receipt

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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.records.PrKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.NoteSheet
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.FinishFailedScreen
import cloud.trotter.log.strength.ui.day.FinishingScreen
import cloud.trotter.log.strength.ui.day.SessionReceiptScreen
import cloud.trotter.log.strength.ui.day.SkippedLift
import cloud.trotter.log.strength.ui.day.workoutDayFixture
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun headerNamesTheFinishedDayPlanDateAndDuration() {
        setReceipt()
        listOf("Squat focus, done.", "Four-day strength", "Tuesday, Nov 3, 58 min").forEach {
            rule.onNodeWithText(it).assertExists()
        }
    }

    @Test
    fun unknownDurationShowsOnlyTheDate() {
        setReceipt(receiptFixture().copy(minutes = null))
        rule.onNodeWithText("Tuesday, Nov 3").assertExists()
        rule.onNodeWithText("Tuesday, Nov 3, 58 min").assertDoesNotExist()
    }

    @Test
    fun collapsedSquatSpeaksOneSentenceAndNeverOpens() {
        setReceipt()
        rule.onNodeWithContentDescription(SQUAT).assertExists().assertHasNoClickAction()
        rule.onNodeWithText("Weight PR", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("240 × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("245 × 5", useUnmergedTree = true).assertExists()
    }

    @Test
    fun curlOpensWithEverySetTheRecordAndThePlan() {
        setReceipt()
        val curl = rule.onNodeWithContentDescription(CURL)
        curl.assertHasClickAction().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        rule.onNodeWithText("Planned 3 × 10 at 35", useUnmergedTree = true).assertDoesNotExist()
        curl.performScrollTo().performClick()
        curl.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        rule.onNodeWithText("Planned 3 × 10 at 35", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("PR, up from 35 × 9", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Set 1, 35 pounds for 10 reps, rep record, up from 35 pounds for 9 reps.").assertExists()
        curl.performClick()
        rule.onNodeWithText("Planned 3 × 10 at 35", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun lighterSetsRepsAndHoldsUseTheirOwnValueLinesAndSentences() {
        setReceipt()
        rule.onNodeWithText("and 3 lighter sets", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Dumbbell Lateral Raise. Heaviest set, 25 pounds for 12 reps, and 3 lighter sets.").assertExists()
        rule.onNodeWithText("12, 10, 9", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Pull-up. 12, 10 and 9 reps.").assertExists()
        rule.onNodeWithText("75s, 70s", useUnmergedTree = true).assertExists()
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
            record = RecordMark(PrKind.WEIGHT, null, 0, ReceiptSet(220.462, 5, 0)),
        )
        setReceipt(receiptFixture().copy(unit = WeightUnit.KG, groups = listOf(ReceiptGroup(null, listOf(row)))))
        rule.onNodeWithText("105 × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("100 × 5", useUnmergedTree = true).assertExists()
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
        rule.onNodeWithText("Records weren't checked for this workout.").assertExists()
        rule.onAllNodesWithText("PR", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Barbell Back Squat. Top set, 245 pounds for 5 reps.").assertExists()
    }

    @Test
    fun allPlansDoneAppearsOnlyWhenTheFrozenReceiptSaysSo() {
        val receipt = mutableStateOf(receiptFixture())
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
    fun cardioHasNeitherShareNorNote() {
        setReceipt(cardioFixture())
        rule.onNodeWithContentDescription("Share").assertDoesNotExist()
        rule.onNodeWithText("Add a note").assertDoesNotExist()
    }

    @Test
    fun cardioIntervalsShowTheFastestAndOpenToRecoveryAndRecord() {
        setReceipt(cardioFixture())
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

    @Test
    fun cardioTimeTargetShowsDistanceAndFartherRecord() {
        setReceipt(timeTargetFixture())
        listOf("30:00", "Distance", "5,210 m", "4,980 m", "Farther PR").forEach {
            rule.onNodeWithText(it, useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithContentDescription("30 minutes. Distance, 5,210 meters. Farther record, up from 4,980 meters.").assertHasNoClickAction()
    }

    @Test
    fun blankCardioDistanceIsNeverZeroAndHasNoRecord() {
        val base = timeTargetFixture()
        val row = base.cardioRows.single()
        setReceipt(base.copy(cardioRows = listOf(row.copy(intervals = listOf(row.intervals.single().copy(metres = null)), record = null))))
        rule.onNodeWithText("Distance not entered", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("0 m", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Farther PR", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("30 minutes. Distance not entered.").assertExists()
    }

    @Test
    fun missingTopSetShowsHeaviestWithoutOpening() {
        val row = receiptFixture().groups.first().rows.single().copy(name = "Romanian Deadlift",
            shape = ReceiptRow.Shape.HEAVIEST_NO_TOP, sets = listOf(ReceiptSet(210.0, 3, 0)),
            lines = listOf(ValueLine(210.0, listOf(0))), record = null)
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row)))))
        listOf("Heaviest", "210 × 3", "top set not logged").forEach { rule.onNodeWithText(it, useUnmergedTree = true).assertExists() }
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
        rule.onNodeWithText("25 × 8, 8, 6", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("1:30 +25", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Loaded Plank. Holds of 1 minute 30 seconds with 25 pounds added. Hold record and weight record on set 1, up from 1 minute 15 seconds.").assertExists()
    }

    @Test
    fun mixedLegacyTrackingAppearsOnlyInTheOpenSetLines() {
        val row = ReceiptRow("Plank", TrackingType.TIMED, ReceiptRow.Shape.STRAIGHT,
            listOf(ReceiptSet(0.0, 60, 0), ReceiptSet(0.0, 0, 60)), listOf(ValueLine(null, listOf(1))),
            0, PlannedSets(2, 0.0, 0, 60), null)
        setReceipt(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row)))))
        rule.onNodeWithText("60s", useUnmergedTree = true).assertExists()
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
        setReceipt(cardioFixture().copy(cardioRows = listOf(row)))
        rule.onNodeWithContentDescription("4 by 400 meters. Fastest, 1 minute 38 seconds. Faster record, up from 1 minute 42 seconds.").performClick()
        rule.onNodeWithText("1:20, shortened", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Interval 3, 1 minute 20 seconds, shortened.").assertExists()
        rule.onNodeWithContentDescription("Interval 4, skipped.").assertExists()
        rule.onNodeWithText("Recovery 1:30, 2:00, 1:30", useUnmergedTree = true).assertExists()
    }

    @Test
    fun entirelySkippedWorkStillOpensToItsIntervals() {
        val row = cardioFixture().cardioRows[1].copy(intervals = List(6) { CardioInterval(CardioSetStatus.SKIPPED, 0, null) }, record = null)
        setReceipt(cardioFixture().copy(cardioRows = listOf(row)))
        rule.onNodeWithText("No interval finished", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("6 by 400 meters. No interval finished.").assertHasClickAction().performClick()
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
        rule.onNodeWithText("245 × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription(SQUAT).assertExists()
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("245 × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription(SQUAT).assertExists()
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("245 × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("240 × 5", useUnmergedTree = true).assertExists()
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
    }

    @Test
    fun reducedMotionShowsFinalValuesImmediately() {
        setAnimatorScale(0f)
        setReceipt()
        rule.onNodeWithText("245 × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("240 × 5", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription(SQUAT).assertExists()
    }

    @Test
    fun openRowsSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { AppTheme { Receipt(receiptFixture()) } }
        rule.onNodeWithContentDescription(CURL).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Planned 3 × 10 at 35", useUnmergedTree = true).assertExists()
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
        rule.onNodeWithContentDescription("Finishing workout")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.mainClock.advanceTimeBy(200)
        rule.onAllNodesWithTag("ghost", useUnmergedTree = true).assertCountEquals(7)
        rule.onNodeWithText("Superset, 3 rounds").assertExists()
        pressBack()
        assertEquals(0, backs)
    }

    @Test
    fun finishingLoadingStateKeepsOnlyTheHeader() {
        rule.setContent { AppTheme { FinishingScreen(attemptFixture(), DayUiState(loading = true)) } }
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithText("Squat focus").assertExists()
        rule.onAllNodesWithTag("ghost", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun finishingShowsSkippedLiftsAfterTheReveal() {
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
        rule.onNodeWithText("Squat focus, done.").assertDoesNotExist()
        rule.onNodeWithText("Try again").performClick()
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
        rule.onNodeWithText("DONE").performClick()
        assertEquals(listOf("x"), saved)
        assertEquals(0, dismissals)
        rule.runOnIdle { save.value = NoteSave.Failed("x") }
        rule.onNode(hasText("Couldn't save the note.") and hasAnyAncestor(isDialog())).assertExists()
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
        rule.onNodeWithText("NOTE").performTextReplacement("Felt steady")
        rule.onNodeWithText("DONE").performClick()
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
        rule.onNodeWithText("NOTE").performTextReplacement("Felt steady")
        rule.onNodeWithText("DONE").performClick()
        assertEquals(listOf("Felt steady"), saved)
        rule.onNodeWithText("NOTE").assertExists()
        rule.runOnIdle { save.value = NoteSave.Saved("Felt steady") }
        rule.onNodeWithText("NOTE").assertDoesNotExist()
        assertEquals(1, handled)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun theReceiptCoversTheWindowButItsContentKeepsTheReadableCap() {
        rule.setContent { AppTheme { Receipt(receiptFixture(), Modifier.testTag("receipt")) } }
        val root = rule.onRoot().fetchSemanticsNode().size
        assertEquals("the receipt must cover the whole window", root, rule.onNodeWithTag("receipt").fetchSemanticsNode().size)
        val readable = with(rule.density) { ReadableWidth.toPx() }
        val headline = rule.onNodeWithText("Squat focus, done.").fetchSemanticsNode().boundsInRoot
        val back = rule.onNodeWithText("Done").fetchSemanticsNode().boundsInRoot
        assertTrue("the ledger is ${back.right - headline.left}px wide, past the $readable px cap", back.right - headline.left <= readable)
        assertTrue("the ledger drifted off center: ${headline.left}px left, ${root.width - back.right}px right",
            abs(headline.left - (root.width - back.right)) <= 2f)
    }

    private fun setReceipt(receipt: WorkoutReceipt = receiptFixture(), onShare: () -> Unit = {}, onFinish: () -> Unit = {}) {
        rule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            AppTheme { SessionReceiptScreen(receipt, onShare, onFinish) }
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
        const val CARDIO = "6 by 400 meters. Fastest, 1 minute 38 seconds. Faster record, up from 1 minute 42 seconds."
    }
}

private fun receiptFixture(): WorkoutReceipt {
    fun weighted(name: String, weights: List<Double>, reps: List<Int>, planned: PlannedSets, record: RecordMark? = null, lighter: Int = 0): ReceiptRow {
        val sets = weights.zip(reps).map { (weight, count) -> ReceiptSet(weight, count, 0) }
        val lines = if (lighter > 0) listOf(ValueLine(weights.first(), listOf(0))) else {
            weights.distinct().sortedDescending().map { weight -> ValueLine(weight, weights.indices.filter { weights[it] == weight }) }
        }
        return ReceiptRow(name, TrackingType.WEIGHTED, ReceiptRow.Shape.STRAIGHT, sets, lines, lighter, planned, record)
    }
    fun single(row: ReceiptRow) = ReceiptGroup(null, listOf(row))
    fun top(name: String, weight: Double, reps: Int, kind: PrKind, previous: ReceiptSet) = single(ReceiptRow(
        name, TrackingType.WEIGHTED, ReceiptRow.Shape.TOP_SET, listOf(ReceiptSet(weight, reps, 0)), listOf(ValueLine(weight, listOf(0))),
        0, null, RecordMark(kind, null, 0, previous),
    ))
    return WorkoutReceipt(
        sessionId = 1, cardio = false, dayId = "A", dayIndex = 0, dayTitle = "Squat focus", planName = "Four-day strength",
        completedAt = LocalDate.of(2026, 11, 3).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        minutes = 58, unit = WeightUnit.LB, recordsChecked = true, allPlansDone = false,
        groups = listOf(
            top("Barbell Back Squat", 245.0, 5, PrKind.WEIGHT, ReceiptSet(240.0, 5, 0)),
            top("Romanian Deadlift", 205.0, 6, PrKind.REPS, ReceiptSet(205.0, 5, 0)),
            ReceiptGroup(3, listOf(
                weighted("Barbell Bench Press", List(3) { 185.0 }, listOf(5, 5, 4), PlannedSets(3, 185.0, 5, 0)),
                weighted("Barbell Row", listOf(155.0, 155.0, 135.0), listOf(8, 8, 10), PlannedSets(3, 155.0, 8, 0)),
            )),
            single(weighted("Dumbbell Curl", listOf(35.0, 35.0, 30.0), listOf(10, 10, 8), PlannedSets(3, 35.0, 10, 0),
                RecordMark(PrKind.REPS, null, 0, ReceiptSet(35.0, 9, 0)))),
            single(weighted("Dumbbell Lateral Raise", listOf(25.0, 20.0, 15.0, 10.0), listOf(12, 10, 10, 12), PlannedSets(3, 25.0, 12, 0), lighter = 3)),
            single(ReceiptRow("Pull-up", TrackingType.REPS, ReceiptRow.Shape.STRAIGHT, listOf(12, 10, 9).map { ReceiptSet(0.0, it, 0) },
                listOf(ValueLine(null, listOf(0, 1, 2))), 0, PlannedSets(3, 0.0, 10, 0), null)),
            single(ReceiptRow("Plank", TrackingType.TIMED, ReceiptRow.Shape.STRAIGHT, listOf(75, 70).map { ReceiptSet(0.0, 0, it) },
                listOf(ValueLine(null, listOf(0, 1))), 0, PlannedSets(2, 0.0, 0, 60), RecordMark(PrKind.HOLD, null, 0, ReceiptSet(0.0, 0, 70)))),
            single(ReceiptRow("Face Pull", TrackingType.WEIGHTED, ReceiptRow.Shape.SKIPPED, emptyList(), emptyList(), 0, null, null)),
        ), activity = null, cardioRows = emptyList(),
    )
}

private fun cardioFixture(): WorkoutReceipt = receiptFixture().copy(
    cardio = true, dayId = "B", dayIndex = 1, dayTitle = "Intervals", planName = "5K build", minutes = 34, allPlansDone = true,
    groups = emptyList(), activity = CardioActivity.TRACK_RUN, cardioRows = listOf(
        CardioReceiptRow(CardioRole.WARMUP, 1, null, null, listOf(CardioInterval(CardioSetStatus.FINISHED, 600, null)), emptyList(), null),
        CardioReceiptRow(CardioRole.WORK, 6, null, 400, listOf(101, 98, 104, 100, 99, 105).map { CardioInterval(CardioSetStatus.FINISHED, it, 400) },
            List(6) { 90 }, CardioRecordMark(PrKind.FASTER, 1, 102, null)),
        CardioReceiptRow(CardioRole.COOLDOWN, 1, null, null, listOf(CardioInterval(CardioSetStatus.FINISHED, 300, null)), emptyList(), null),
    ),
)

private fun timeTargetFixture(): WorkoutReceipt = cardioFixture().copy(dayTitle = "Easy run", minutes = 30, activity = CardioActivity.ROAD_RUN,
    cardioRows = listOf(CardioReceiptRow(CardioRole.WORK, 1, 1800, null, listOf(CardioInterval(CardioSetStatus.FINISHED, 1800, 5210)),
        emptyList(), CardioRecordMark(PrKind.FARTHER, 0, null, 4980))))

private fun attemptFixture(): FinishAttempt {
    val receipt = receiptFixture()
    return FinishAttempt("run", 1, receipt.dayId, receipt.dayIndex, receipt.dayTitle, receipt.planName,
        receipt.completedAt - 58 * 60_000, receipt.completedAt)
}

private fun finishingFixture(): DayUiState {
    val base = workoutDayFixture()
    val source = base.exercises.first()
    val names = listOf("Barbell Back Squat", "Romanian Deadlift", "Barbell Bench Press", "Dumbbell Curl", "Dumbbell Lateral Raise", "Pull-up")
    return base.copy(exercises = names.mapIndexed { index, name ->
        source.copy(programExerciseId = index + 1L, position = index, title = name, isSuperset = index == 2,
            partnerTitle = if (index == 2) "Barbell Row" else null, rows = source.rows.take(3))
    })
}
