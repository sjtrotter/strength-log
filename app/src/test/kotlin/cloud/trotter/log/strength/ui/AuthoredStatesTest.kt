package cloud.trotter.log.strength.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.components.LOADING_REVEAL_DELAY_MS
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
 * Issue #127's edge states. What's pinned here is the *distinction*, not the
 * pixels: a screen that is still reading the program must not accuse the lifter
 * of not having one, a screen that knows there isn't one must offer the way
 * back to the wizard, and the empty journal must offer the workout that fills
 * it. The reveal delay is pinned too — it is the whole reason the loading
 * treatment doesn't flash on a fast open.
 *
 * [cloud.trotter.log.strength.ui.components.ProgramLoadingState]'s rule animates
 * forever, so every test that renders it turns the test clock's auto-advance
 * off first; left on, the rule's next frame is always pending and the test
 * would wait for an idle that never comes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class AuthoredStatesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --- program loading ----------------------------------------------------

    @Test
    fun dayLoadingWaitsBeforeRevealingTheProgramMessage() {
        composeTestRule.mainClock.autoAdvance = false
        setDayContent(DayUiState(loading = true))

        composeTestRule.mainClock.advanceTimeBy(LOADING_REVEAL_DELAY_MS - 60)
        composeTestRule.onNodeWithText("PREPARING YOUR PROGRAM").assertDoesNotExist()
        composeTestRule.onNodeWithText("NO PROGRAM YET").assertDoesNotExist()

        composeTestRule.mainClock.advanceTimeBy(120)
        composeTestRule.onNodeWithText("PREPARING YOUR PROGRAM").assertIsDisplayed()
        composeTestRule.onNodeWithText("NO PROGRAM YET").assertDoesNotExist()
    }

    @Test
    fun todayLoadingWaitsBeforeRevealingTheProgramMessage() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            AppTheme { TodayScreen(TodayUiState(loading = true), todayActions()) }
        }

        composeTestRule.mainClock.advanceTimeBy(LOADING_REVEAL_DELAY_MS - 60)
        composeTestRule.onNodeWithText("PREPARING YOUR PROGRAM").assertDoesNotExist()
        composeTestRule.onNodeWithText("NO PROGRAM YET").assertDoesNotExist()

        composeTestRule.mainClock.advanceTimeBy(120)
        composeTestRule.onNodeWithText("PREPARING YOUR PROGRAM").assertIsDisplayed()
        composeTestRule.onNodeWithText("NO PROGRAM YET").assertDoesNotExist()
    }

    // --- no program ---------------------------------------------------------

    @Test
    fun dayWithoutAProgramOffersTheSetupWizard() {
        var opened = 0
        setDayContent(DayUiState(hasProgram = false, loading = false), onSetUpProgram = { opened++ })

        composeTestRule.onNodeWithText("NO PROGRAM YET").assertIsDisplayed()
        composeTestRule.onNodeWithText("RUN THE SETUP WIZARD").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun todayWithoutAProgramOffersTheSetupWizard() {
        var opened = 0
        composeTestRule.setContent {
            AppTheme { TodayScreen(TodayUiState(), todayActions(onSetUpProgram = { opened++ })) }
        }

        composeTestRule.onNodeWithText("NO PROGRAM YET").assertIsDisplayed()
        composeTestRule.onNodeWithText("RUN THE SETUP WIZARD").performClick()

        assertEquals(1, opened)
    }

    // --- empty journal ------------------------------------------------------

    @Test
    fun emptyLogOffersToStartASession() {
        var started = 0
        setLogContent(LogUiState(), onStartSession = { started++ })

        composeTestRule.onNodeWithText("YOUR FIRST SESSION WILL LAND HERE").assertIsDisplayed()
        composeTestRule.onNodeWithText("START A SESSION").performClick()

        assertEquals(1, started)
    }

    @Test
    fun emptyLogWithoutAProgramOffersTheWizardRatherThanASessionItCannotStart() {
        var opened = 0
        setLogContent(LogUiState(hasProgram = false), onSetUpProgram = { opened++ })

        composeTestRule.onNodeWithText("START A SESSION").assertDoesNotExist()
        composeTestRule.onNodeWithText("NO PROGRAM YET").assertIsDisplayed()
        composeTestRule.onNodeWithText("RUN THE SETUP WIZARD").performClick()

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

        composeTestRule.onNodeWithText("YOUR FIRST SESSION WILL LAND HERE").assertDoesNotExist()
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
        onSelectDay = {},
        onWeightChange = { _, _, _, _ -> },
        onRepsChange = { _, _, _, _ -> },
        onSecondsChange = { _, _, _, _ -> },
        onToggleDone = { _, _, _, _ -> },
        onAddSet = { _, _ -> },
        onRemoveSet = { _, _, _ -> },
        onToggleCollapse = {},
        onKeepScreenOnChange = {},
        onClearChecks = {},
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
        onOpenLog = {},
        onSetUpProgram = onSetUpProgram,
    )

    private fun logActions(
        onStartSession: () -> Unit = {},
        onSetUpProgram: () -> Unit = {},
    ) = LogActions(
        onBack = {},
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
