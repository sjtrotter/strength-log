package cloud.trotter.log.strength.ui.day

import android.animation.ValueAnimator
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.time.LocalDate
import java.time.ZoneId
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

        listOf("Day A, done.", "Friday, Oct 30", "18 sets in 52 min", "New top set",
            "Next", "Day B, Upper", "Add a note", "Done").forEach {
            composeTestRule.onNodeWithText(it).assertExists()
        }
        composeTestRule.onNodeWithContentDescription("Barbell Back Squat, new top set 245, up from 235").assertExists()
        composeTestRule.onNodeWithContentDescription("Share").assertHasClickAction()
    }

    @Test
    fun partialReceiptShowsCompletedAndTotalSetsAndMinutes() {
        setDayContent(dayState(done = 3), receipt = receipt.copy(completedSetCount = 9, minutes = 31))
        composeTestRule.onNodeWithText("Day A, done.").assertExists()
        composeTestRule.onNodeWithText("9 of 18 sets in 31 min").assertExists()
    }

    @Test
    fun unknownStartShowsSetsWithoutInventingMinutes() {
        setDayContent(dayState(done = 3), receipt = receipt.copy(minutes = null, newTopSets = emptyList()))
        composeTestRule.onNodeWithText("18 sets").assertExists()
        composeTestRule.onNodeWithText("New top set").assertDoesNotExist()
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
        val json = CardioBlockJson.encode(requireNotNull(cardioFixture().block))
        val cardio = CardioRun(1L, "Plan", "A", 0, json, json, true, "Day B, Upper",
            results = listOf(SavedCardioResult(CardioSetStatus.FINISHED.name, 300)))
        setDayContent(dayState(done = 3), receipt = receipt.copy(cardio = cardio, newTopSets = emptyList(), note = "Strength note"))
        composeTestRule.onNodeWithText("Day A, done.").assertExists()
        composeTestRule.onNodeWithText("Road run").assertExists()
        composeTestRule.onNodeWithText("Logged 5:00").assertExists()
        composeTestRule.onNodeWithContentDescription("Share").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add a note").assertDoesNotExist()
        composeTestRule.onNodeWithText("Edit note").assertDoesNotExist()
        composeTestRule.onNodeWithText("Strength note").assertDoesNotExist()
        composeTestRule.onNodeWithText("Done").assertHasClickAction()
    }

    @Test
    fun reducedMotionShowsBothFinalNumbersImmediately() {
        setAnimatorScale(0f)
        composeTestRule.mainClock.autoAdvance = false
        setDayContent(dayState(done = 3), receipt = receipt)
        composeTestRule.onNodeWithText("235", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("245", useUnmergedTree = true).assertExists()
    }

    @Test
    fun restoringDuringTheStrikeShowsTheEndStateWithoutAnotherHaptic() {
        val restoration = StateRestorationTester(composeTestRule)
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                feedback += hapticFeedbackType
            }
        }
        composeTestRule.mainClock.autoAdvance = false
        restoration.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                AppTheme { SessionReceiptScreen(receipt, {}, {}) }
            }
        }
        composeTestRule.mainClock.advanceTimeBy(160)
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback)

        // A paused test clock never recomposes the restored content, so the clock
        // runs from here. The saved `played` flag gates both the haptic and the
        // animation, so a single haptic after restore also means no replay.
        composeTestRule.mainClock.autoAdvance = true
        restoration.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("235", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("245", useUnmergedTree = true).assertExists()
        assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
    }

    @Test
    fun theRollPreservesFractionalWeights() {
        setDayContent(dayState(done = 3), receipt = receipt.copy(
            newTopSets = listOf(CascadeLift("Barbell Back Squat", "100.25", "102.75"))))
        composeTestRule.onNodeWithText("100.25", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("102.75", useUnmergedTree = true).assertExists()
    }

    @Test
    fun noteOpensOnTheReceiptAndReportsThroughTheSharedSheet() {
        var savedNote = ""
        composeTestRule.setContent {
            AppTheme { SessionReceiptScreen(receipt, {}, {}, onSetReceiptNote = { savedNote = it }) }
        }
        composeTestRule.onNodeWithText("Add a note").performClick()
        composeTestRule.onNodeWithText("NOTE").performTextReplacement("Felt steady")
        composeTestRule.onNodeWithText("DONE").performClick()
        assertEquals("Felt steady", savedNote)
    }

    @Test
    fun existingNoteShowsItsTextAndEditAction() {
        setDayContent(dayState(done = 3), receipt = receipt.copy(note = "Felt steady"))
        composeTestRule.onNodeWithText("Felt steady").assertExists()
        composeTestRule.onNodeWithText("Edit note").assertHasClickAction()
        composeTestRule.onNodeWithText("Add a note").assertDoesNotExist()
    }

    @Test
    fun largeFontCanScrollToDone() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme { SessionReceiptScreen(receipt.copy(note = "A note about the session"), {}, {}) }
            }
        }
        composeTestRule.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
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
                    dayEditState = dayEditState, dayEditActions = noopEditActions(), sessionReceipt = receipt)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(0, countSheets)
        assertEquals(0, distanceSheets)
    }

    private var backDispatcher: OnBackPressedDispatcher? = null

    private fun pressBack() {
        composeTestRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()
    }

    private val receipt = SessionReceipt(
        sessionId = 1L,
        dayId = "A",
        dayIndex = 0,
        completedAt = LocalDate.of(2026, 10, 30).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        minutes = 52,
        completedSetCount = 18,
        totalSetCount = 18,
        setCount = 18,
        newTopSets = listOf(CascadeLift("Barbell Back Squat", "235", "245")),
        nextDayLine = "Day B, Upper",
    )

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
                isMain = false,
                isSuperset = false,
                hasWarmupHint = false,
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
        receipt: SessionReceipt? = null,
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
                    sessionReceipt = receipt,
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
