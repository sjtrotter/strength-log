package cloud.trotter.log.strength.ui.day

import android.animation.ValueAnimator
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.receipt.FinishAttempt
import cloud.trotter.log.strength.ui.day.receipt.NoteSave
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSet
import cloud.trotter.log.strength.ui.day.receipt.ValueLine
import cloud.trotter.log.strength.ui.day.receipt.WorkoutReceipt
import cloud.trotter.log.strength.ui.day.receipt.cardioReceiptFixture
import cloud.trotter.log.strength.ui.day.receipt.receiptFixture
import cloud.trotter.log.strength.ui.theme.AppTheme
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

/** Pins workout progress and the single receipt that replaces it after Finish. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SessionLifecycleScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var originalDurationScale = 1f

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

    // --- progress folded into the day overline -------------------------------

    @Test
    fun aDayWithNoTicksShowsZeroInTheAppBar() {
        setDayContent(dayState(done = 0))

        composeTestRule.onNodeWithContentDescription("Day A lifts, 0 of 3 sets").assertExists()
    }

    @Test
    fun theAppBarShowsTheSavedSetCount() {
        setDayContent(dayState(done = 1))

        composeTestRule.onNodeWithContentDescription("Day A lifts, 1 of 3 sets").assertExists()
    }

    @Test
    fun theAppBarReachesTheTotalWhenEveryRoundIsTicked() {
        setDayContent(dayState(done = 3))

        composeTestRule.onNodeWithContentDescription("Day A lifts, 3 of 3 sets").assertExists()
    }

    @Test
    fun theAppBarCountsRoundsAcrossEveryPage() {
        val base = workoutDayFixture()
        val cards = List(7) { index ->
            val source = base.exercises[index.coerceAtMost(2)]
            source.copy(programExerciseId = index + 1L, position = index)
        }
        setDayContent(base.copy(exercises = cards))
        composeTestRule.onNodeWithContentDescription("Day D lifts, 4 of 21 sets").assertExists()
    }

    @Test
    fun theReceiptReadsBackTheSessionThatJustEnded() {
        setDayContent(dayState(done = 3), receipt = receipt)

        listOf("Squat focus, done.", "Four-day strength", "Tuesday, Nov 3, 58 min", "Weight PR",
            "Add a note", "Done").forEach {
            composeTestRule.onNodeWithText(it, useUnmergedTree = true).assertExists()
        }
        composeTestRule.onNodeWithContentDescription("Barbell Back Squat. Top set, 245 pounds for 5 reps. Weight record, up from 240 pounds for 5 reps.").assertExists()
        composeTestRule.onNodeWithContentDescription("Share").assertHasClickAction()
    }

    @Test
    fun partialReceiptShowsOnlyTheLoggedSetsAndMinutes() {
        val curlGroup = receiptFixture().groups.first { group -> group.rows.any { it.name == "Dumbbell Curl" } }
        val curl = curlGroup.rows.single()
        val partial = curl.copy(sets = curl.sets.take(2), lines = listOf(ValueLine(35.0, listOf(0, 1))))
        setDayContent(dayState(done = 2), receipt = receipt.copy(minutes = 31,
            groups = listOf(curlGroup.copy(rows = listOf(partial)))))
        composeTestRule.onNodeWithText("Squat focus, saved.").assertExists()
        composeTestRule.onNodeWithText("2 of 3 sets logged").assertExists()
        composeTestRule.onNodeWithText("Tuesday, Nov 3, 31 min").assertExists()
        composeTestRule.onNodeWithText("35 lb × 10, 10", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Dumbbell Curl. 35 pounds: 10 and 10 reps. Rep record on set 1, up from 35 pounds for 9 reps.").performClick()
        composeTestRule.onNodeWithText("Planned 3 × 10 at 35 lb", useUnmergedTree = true).assertExists()
    }

    @Test
    fun unknownStartShowsTheDateWithoutInventingMinutes() {
        setDayContent(dayState(done = 3), receipt = receipt.copy(minutes = null, groups = receipt.groups.map { group -> group.copy(rows = group.rows.map { it.copy(record = null) }) }))
        composeTestRule.onNodeWithText("Tuesday, Nov 3, Duration not recorded").assertExists()
        composeTestRule.onNodeWithText("245 lb × 5", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Weight PR", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun shareAndDoneReportOutward() {
        var shares = 0
        var finishes = 0
        setDayContent(dayState(done = 3), receipt = receipt, onShare = { shares++ }, onFinish = { finishes++ })
        composeTestRule.onNodeWithContentDescription("Share").performClick()
        composeTestRule.onNodeWithText("Done").assertHasClickAction().performClick()
        assertEquals(1, shares)
        assertEquals(1, finishes)
    }

    @Test
    fun theWorkoutIsAbsentWhileTheReceiptIsShown() {
        setDayContent(dayState(done = 3), receipt = receipt)
        composeTestRule.onNodeWithText("Finish", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Day A lifts, 3 of 3 sets", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("All sets", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun systemBackFinishesTheSingleReceipt() {
        var finishes = 0
        setDayContent(dayState(done = 3), receipt = receipt, onFinish = { finishes++ })
        pressBack()
        assertEquals(1, finishes)
    }

    @Test
    fun cardioKeepsItsResultsWithoutShareOrNote() {
        setDayContent(dayState(done = 3), receipt = cardioReceiptFixture().copy(note = "Strength note"))
        composeTestRule.onNodeWithText("Intervals, done.").assertExists()
        composeTestRule.onNodeWithText("Track run").assertExists()
        composeTestRule.onNodeWithText("1:38", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("5:00", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Share").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add a note").assertDoesNotExist()
        composeTestRule.onNodeWithText("Edit note").assertDoesNotExist()
        composeTestRule.onNodeWithText("Strength note").assertDoesNotExist()
        composeTestRule.onNodeWithText("Done").assertHasClickAction()
    }

    @Test
    fun reducedMotionShowsBothFinalNumbersImmediately() {
        setAnimatorScale(0f)
        setDayContent(dayState(done = 3), receipt = receipt)
        composeTestRule.onNodeWithText("240 lb × 5", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("245 lb × 5", useUnmergedTree = true).assertExists()
    }

    @Test
    fun restoringDuringTheRollShowsTheEndStateWithoutAnotherHaptic() {
        val restoration = StateRestorationTester(composeTestRule)
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                feedback += hapticFeedbackType
            }
        }
        restoration.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                AppTheme { SessionReceiptScreen(receipt, {}, {}) }
            }
        }
        composeTestRule.mainClock.advanceTimeBy(160)
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback)

        restoration.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("240 lb × 5", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("245 lb × 5", useUnmergedTree = true).assertExists()
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
    }

    @Test
    fun theRollPreservesFractionalWeights() {
        val group = receipt.groups.single()
        val row = group.rows.single()
        setDayContent(dayState(done = 3), receipt = receipt.copy(groups = listOf(group.copy(rows = listOf(row.copy(
            sets = listOf(ReceiptSet(102.75, 5, 0)),
            lines = row.lines.map { it.copy(weightLb = 102.75) },
            record = requireNotNull(row.record).copy(previous = ReceiptSet(100.25, 5, 0)),
        ))))))
        composeTestRule.onNodeWithText("100.25 lb × 5", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("102.75 lb × 5", useUnmergedTree = true).assertExists()
    }

    @Test
    fun noteOpensOnTheReceiptAndReportsThroughTheSharedSheet() {
        var savedNote = ""
        val save = mutableStateOf<NoteSave?>(null)
        composeTestRule.setContent {
            AppTheme {
                DayScreen(dayState(done = 3), dayActions().copy(onSetSessionNote = {
                    savedNote = it
                    save.value = NoteSave.Saved(it)
                }), dayEditState, noopEditActions(), receipt = receipt, noteSave = save.value,
                    onNoteSaveHandled = { save.value = null })
            }
        }
        composeTestRule.onNodeWithText("Add a note").performClick()
        composeTestRule.onNodeWithText("Note").performTextReplacement("Felt steady")
        composeTestRule.onNodeWithText("Save note").performClick()
        assertEquals("Felt steady", savedNote)
        composeTestRule.onNodeWithText("Note").assertDoesNotExist()
    }

    @Test
    fun existingNoteShowsItsTextAndEditAction() {
        setDayContent(dayState(done = 3), receipt = receipt.copy(note = "Felt steady"))
        composeTestRule.onNodeWithText("Felt steady").assertExists()
        composeTestRule.onNodeWithText("Edit note").assertHasClickAction()
        composeTestRule.onNodeWithText("Add a note").assertDoesNotExist()
    }

    @Test
    fun largeFontKeepsDoneVisible() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme { SessionReceiptScreen(receipt.copy(note = "A note about the session"), {}, {}) }
            }
        }
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun receiptSuppressesTimedRepAndDistanceAutoSheets() {
        var countSheets = 0
        var distanceSheets = 0
        val base = workoutSetTimedRepRunningFixture(awaiting = true)
        val pending = requireNotNull(base.timedRep)
        val state = base.copy(timedRep = pending.copy(event = pending.event.copy(sheetDismissed = false)))
        assertTrue(requireNotNull(state.timedRep).requestsSheet)
        val run = requireNotNull(runningCardioFixture().run).copy(drafts = listOf(CardioDistanceDraft(0, true)))
        composeTestRule.setContent {
            AppTheme {
                DayScreen(state.copy(cardio = cardioFixture().copy(distanceRuns = listOf(run))),
                    actions = dayActions().copy(onOpenTimedRepCount = { countSheets++ }, onOpenCardioDistance = { distanceSheets++ }),
                    dayEditState = dayEditState, dayEditActions = noopEditActions(), receipt = receipt)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(0, countSheets)
        assertEquals(0, distanceSheets)
    }

    @Test
    fun finishingReplacesTheWorkoutAndSwallowsBack() {
        composeTestRule.mainClock.autoAdvance = false
        var backs = 0
        setDayContent(dayState(done = 3), finish = attempt(), onBackToWorkout = { backs++ })
        composeTestRule.mainClock.advanceTimeBy(300)
        composeTestRule.onNodeWithText("Saving your workout…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Finish", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Day A lifts, 3 of 3 sets").assertDoesNotExist()
        pressBack()
        assertEquals(0, backs)
    }

    @Test
    fun failedFinishOffersRetryAndBackWithoutTheWorkout() {
        var retries = 0
        var backs = 0
        setDayContent(dayState(done = 3), finish = attempt().copy(failed = true),
            onRetry = { retries++ }, onBackToWorkout = { backs++ })
        composeTestRule.onNodeWithText("Try again").performClick()
        composeTestRule.onNodeWithText("Back to workout").performClick()
        pressBack()
        assertEquals(1, retries)
        assertEquals(2, backs)
        composeTestRule.onNodeWithText("Finish", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun attempt() = FinishAttempt("run", 1L, "A", 0, receipt.dayTitle, receipt.planName, null, receipt.completedAt)

    private var backDispatcher: OnBackPressedDispatcher? = null

    private fun pressBack() {
        composeTestRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()
    }

    private val receipt = receiptFixture().let { it.copy(groups = it.groups.take(1)) }

    private fun dayState(done: Int) = DayUiState(
        hasProgram = true,
        viewDayId = "A",
        dayIndex = 0,
        dayTitle = "Lower",
        emphasisLine = "",
        unit = WeightUnit.LB,
        nextDayId = "B",
        exercises = listOf(
            ExerciseCardState(
                programExerciseId = 1L,
                position = 0,
                title = "Barbell Back Squat",
                isSuperset = false,
                headerTarget = HeaderTarget.Goal("235"),
                perHand = false,
                allDone = done >= 3,
                rows = List(3) { index ->
                    SetRowState(
                        index = index,
                        kindLabel = "${index + 1}",
                        isTop = false,
                        weightDisplay = 135.0,
                        reps = 5,
                        done = index < done,
                    )
                },
            ),
        ),
    )

    private val dayEditState = DayEditUiState(catalog = ExerciseCatalog.CODE_ONLY)

    private fun setDayContent(
        state: DayUiState,
        receipt: WorkoutReceipt? = null,
        finish: FinishAttempt? = null,
        onRetry: () -> Unit = {},
        onBackToWorkout: () -> Unit = {},
        onShare: () -> Unit = {},
        onFinish: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AppTheme {
                backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                DayScreen(
                    state = state.withWorkoutSelection(),
                    actions = dayActions(),
                    dayEditState = dayEditState,
                    dayEditActions = noopEditActions(),
                    receipt = receipt,
                    finish = finish,
                    onRetryFinish = onRetry,
                    onBackToWorkout = onBackToWorkout,
                    onBack = onBackToWorkout,
                    onShareSession = onShare,
                    onFinishSession = onFinish,
                )
            }
        }
    }

    private fun dayActions() = DayActions(
        onWeightChange = { _, _, _, _ -> },
        onRepsChange = { _, _, _, _ -> },
        onSecondsChange = { _, _, _, _ -> },
        onToggleDone = { _, _, _, _ -> },
        onAddSet = { _, _ -> },
        onRemoveSet = { _, _, _ -> },
        onDiscardWorkout = {},
        onDone = {},
        onCreateExercise = {},
        onSetUpProgram = {},
    )

    private fun noopEditActions() = DayEditActions(
        onSwap = { _, _ -> },
        onAdd = {},
        onRemove = {},
        onSetSuperset = { _, _ -> },
        onRemoveSuperset = {},
        onResetToTemplate = {},
    )
}
