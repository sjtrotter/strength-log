package cloud.trotter.log.strength.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.components.EmptyJournalState
import cloud.trotter.log.strength.ui.components.LOADING_REVEAL_DELAY_MS
import cloud.trotter.log.strength.ui.components.LoadingState
import cloud.trotter.log.strength.ui.day.DayActions
import cloud.trotter.log.strength.ui.day.DayEditActions
import cloud.trotter.log.strength.ui.day.DayEditUiState
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.log.LogActions
import cloud.trotter.log.strength.ui.log.LogScreen
import cloud.trotter.log.strength.ui.log.LogUiState
import cloud.trotter.log.strength.ui.log.SessionListItem
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edge states. What's pinned here is the *distinction*, not the pixels: a
 * screen that is still reading the program must not accuse the lifter of not
 * having one, a screen that knows there isn't one must offer the way back to
 * the wizard, and the empty journal must offer the workout that fills it. The
 * reveal delay is pinned too — it is the whole reason the loading treatment
 * doesn't flash on a fast open.
 *
 * Loading tests control the main clock to pin the reveal delay and the
 * indeterminate Material indicator's animation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ScreenStatesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loadingDrawsNothingBeforeTheDelayThenShowsProgressAndAPoliteLabel() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { AppTheme { LoadingState("Loading…") } }
        composeTestRule.mainClock.advanceTimeBy(LOADING_REVEAL_DELAY_MS - 60)
        composeTestRule.onNodeWithText("Loading…").assertDoesNotExist()
        val indicator = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        composeTestRule.onNode(indicator).assertDoesNotExist()

        composeTestRule.mainClock.advanceTimeBy(120)
        composeTestRule.onNode(indicator).assertIsDisplayed()
        composeTestRule.onNodeWithText("Loading…").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun journalWithAKnownDayNamesItsStartAction() {
        var starts = 0
        composeTestRule.setContent { AppTheme {
            EmptyJournalState(true, { starts++ }, {}, dayId = "B")
        } }
        composeTestRule.onNodeWithText("No sessions yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Finished workouts show up here.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start day B").performClick()
        assertEquals(1, starts)
    }

    @Test
    fun emptyWorkoutDayOffersItsExistingEditor() {
        var edited = false
        var opened = false
        composeTestRule.setContent { AppTheme {
            DayScreen(
                DayUiState(hasProgram = true, viewDayId = "A"),
                dayActions().copy(onOpenDaySheet = { opened = true }), DayEditUiState(),
                dayEditActions().copy(onEditorOpen = { edited = it }),
            )
        } }
        composeTestRule.onNodeWithText("No lifts in day A yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit day").performClick()
        assertEquals(true, edited)
        composeTestRule.onNodeWithContentDescription("Day A lifts, 0 of 0 sets").performClick()
        assertEquals(true, opened)
    }

    // --- program loading ----------------------------------------------------

    @Test
    fun dayLoadingWaitsBeforeRevealingTheProgramMessage() {
        composeTestRule.mainClock.autoAdvance = false
        setDayContent(DayUiState(loading = true))

        composeTestRule.mainClock.advanceTimeBy(LOADING_REVEAL_DELAY_MS - 60)
        composeTestRule.onNodeWithText("Loading…").assertDoesNotExist()
        composeTestRule.onNodeWithText("No plan yet").assertDoesNotExist()

        composeTestRule.mainClock.advanceTimeBy(120)
        composeTestRule.onNodeWithText("Loading…").assertIsDisplayed()
        composeTestRule.onNodeWithText("No plan yet").assertDoesNotExist()
    }

    @Test
    fun todayLoadingWaitsBeforeRevealingTheProgramMessage() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            AppTheme { TodayScreen(TodayUiState(loading = true), todayActions()) }
        }

        composeTestRule.mainClock.advanceTimeBy(LOADING_REVEAL_DELAY_MS - 60)
        composeTestRule.onNodeWithText("Loading…").assertDoesNotExist()
        composeTestRule.onNodeWithText("No plan yet").assertDoesNotExist()

        composeTestRule.mainClock.advanceTimeBy(120)
        composeTestRule.onNodeWithText("Loading…").assertIsDisplayed()
        composeTestRule.onNodeWithText("No plan yet").assertDoesNotExist()
    }

    // --- no program ---------------------------------------------------------

    @Test
    fun dayWithoutAProgramOffersTheSetupWizard() {
        var opened = 0
        setDayContent(DayUiState(hasProgram = false, loading = false), onSetUpProgram = { opened++ })

        composeTestRule.onNodeWithText("No plan yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Set up a plan").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun todayWithoutAProgramOffersTheSetupWizard() {
        var opened = 0
        composeTestRule.setContent {
            AppTheme { TodayScreen(TodayUiState(), todayActions(onSetUpProgram = { opened++ })) }
        }

        composeTestRule.onNodeWithText("No plan yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Set up a plan").performClick()

        assertEquals(1, opened)
    }

    // --- empty journal ------------------------------------------------------

    @Test
    fun emptyLogOffersToStartASession() {
        var started = 0
        setLogContent(LogUiState(), onStartSession = { started++ })

        composeTestRule.onNodeWithText("No sessions yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start a session").performClick()

        assertEquals(1, started)
    }

    @Test
    fun emptyLogWithoutAProgramOffersTheWizardRatherThanASessionItCannotStart() {
        var opened = 0
        setLogContent(LogUiState(hasProgram = false), onSetUpProgram = { opened++ })

        composeTestRule.onNodeWithText("Start a session").assertDoesNotExist()
        composeTestRule.onNodeWithText("No plan yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Set up a plan").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun logWithASessionDoesNotShowTheEmptyState() {
        setLogContent(
            LogUiState(
                sessions = listOf(
                    SessionListItem(
                        sessionId = 1L,
                        dateDisplay = "Aug 6, 2026",
                        dayLetter = "A",
                        dayIndex = 0,
                        dayTitle = "Test",
                        setCount = 1,
                        bodyweightDisplay = "235 LB",
                        expanded = false,
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText("No sessions yet").assertDoesNotExist()
    }

    // --- fixtures -----------------------------------------------------------

    private fun setDayContent(state: DayUiState, onSetUpProgram: () -> Unit = {}) {
        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = state,
                    actions = dayActions(onSetUpProgram),
                    dayEditState = DayEditUiState(),
                    dayEditActions = dayEditActions(),
                )
            }
        }
    }

    private fun setLogContent(
        state: LogUiState,
        onStartSession: () -> Unit = {},
        onSetUpProgram: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AppTheme { LogScreen(state, logActions(onStartSession, onSetUpProgram)) }
        }
    }

    private fun dayActions(onSetUpProgram: () -> Unit = {}) = DayActions(
        onWeightChange = { _, _, _, _ -> },
        onRepsChange = { _, _, _, _ -> },
        onSecondsChange = { _, _, _, _ -> },
        onToggleDone = { _, _, _, _ -> },
        onAddSet = { _, _ -> },
        onRemoveSet = { _, _, _ -> },
        onDiscardWorkout = {},
        onDone = {},
        onCreateExercise = { _: MovementPattern -> },
        onSetUpProgram = onSetUpProgram,
    )

    private fun dayEditActions() = DayEditActions(
        onSwap = { _, _ -> },
        onAdd = {},
        onRemove = {},
        onSetSuperset = { _, _ -> },
        onRemoveSuperset = {},
        onResetToTemplate = {},
    )

    private fun todayActions(onSetUpProgram: () -> Unit = {}) = TodayActions(
        onStart = {},
        onOpenSettings = {},
        onSetUpProgram = onSetUpProgram,
    )

    private fun logActions(
        onStartSession: () -> Unit = {},
        onSetUpProgram: () -> Unit = {},
    ) = LogActions(
        onToggleExpanded = {},
        onPageCalendar = {},
        onConnectHealth = {},
        onPublishPastWorkouts = {},
        onApplyBodyweight = {},
        onDismissBodyweight = {},
        onShare = {},
        onStartSession = onStartSession,
        onSetUpProgram = onSetUpProgram,
    )
}
