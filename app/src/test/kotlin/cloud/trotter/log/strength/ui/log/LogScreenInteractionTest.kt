package cloud.trotter.log.strength.ui.log

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.audit.AuditDestination
import cloud.trotter.log.strength.audit.auditHistoryFixture
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.navigation.TopLevelDestination
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the session card's interaction contract: disclosure lives on the header
 * row — one owner per action — and SHARE is a sibling that never also toggles
 * the row.
 *
 * Also pins the Health Connect section's publishing states and its backfill
 * offer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class LogScreenInteractionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        expanded: Boolean,
        health: HealthSectionUi = HealthSectionUi(),
        onToggle: (Long) -> Unit = {},
        onShare: (Long) -> Unit = {},
        onConnectHealth: () -> Unit = {},
        onPublishPast: () -> Unit = {},
        sessions: List<SessionListItem> = listOf(logSessionFixture(expanded)),
        journal: JournalUiState = JournalUiState(),
        restoration: StateRestorationTester? = null,
        withNavigation: Boolean = false,
    ) {
        val content: @Composable () -> Unit = {
            AppTheme {
                val screen: @Composable () -> Unit = {
                    LogScreen(
                        LogUiState(sessions = sessions, health = health, journal = journal),
                        LogActions(
                            onToggleExpanded = onToggle,
                            onPageCalendar = {},
                            onConnectHealth = onConnectHealth,
                            onPublishPastWorkouts = onPublishPast,
                            onApplyBodyweight = {},
                            onDismissBodyweight = {},
                            onShare = onShare,
                            onStartSession = {},
                            onSetUpProgram = {},
                        ),
                    )
                }
                if (withNavigation) AuditDestination(TopLevelDestination.LOG, screen) else screen()
            }
        }
        if (restoration == null) composeTestRule.setContent(content) else restoration.setContent(content)
    }

    @Test
    fun unnamedHistoryKeepsTheExistingDateLine() {
        setContent(expanded = false)

        composeTestRule.onNodeWithText("Jul 6, 2026").assertIsDisplayed()
    }

    @Test
    fun onePlanRenamedBetweenSessionsKeepsAllExistingDateLines() {
        setContent(
            expanded = false,
            sessions = listOf(
                logSessionFixture(false).copy(planId = 1, planName = "Test prep"),
                logSessionFixture(false).copy(sessionId = 8, dateDisplay = "Jul 3, 2026", planId = 1, planName = "Old name"),
                logSessionFixture(false).copy(sessionId = 9, dateDisplay = "Jun 30, 2026"),
            ),
        )

        composeTestRule.onNodeWithText("Jul 6, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("Jul 3, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("Jun 30, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test prep · Jul 6, 2026").assertDoesNotExist()
        composeTestRule.onNodeWithText("Old name · Jul 3, 2026").assertDoesNotExist()
    }

    @Test
    fun onePlanRenamedBetweenStrengthAndCardioKeepsDateLines() {
        setContent(
            expanded = false,
            sessions = listOf(
                logSessionFixture(false).copy(planId = 1, planName = "Test prep"),
                logSessionFixture(false).copy(
                    sessionId = 8, dateDisplay = "Jul 3, 2026", dayTitle = "Easy Zone 2",
                    cardioId = 8, cardioSummary = "OUTDOOR RUN", cardioDuration = "20:00",
                    cardioSemantics = "Easy Zone 2", planId = 1, planName = "Old name",
                ),
            ),
        )

        composeTestRule.onNodeWithText("Jul 6, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("Jul 3, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test prep · Jul 6, 2026").assertDoesNotExist()
        composeTestRule.onNodeWithText("Old name · Jul 3, 2026").assertDoesNotExist()
    }

    @Test
    fun distinctStrengthAndCardioPlansShowTheirCopiedNames() {
        setContent(
            expanded = false,
            sessions = listOf(
                logSessionFixture(false).copy(planId = 1, planName = "Test prep"),
                logSessionFixture(false).copy(
                    sessionId = 8, dateDisplay = "Jul 3, 2026", dayTitle = "Easy Zone 2",
                    cardioId = 8, cardioSummary = "OUTDOOR RUN", cardioDuration = "20:00",
                    cardioSemantics = "Easy Zone 2", planId = 2, planName = "Run plan",
                ),
                logSessionFixture(false).copy(sessionId = 9, dateDisplay = "Jun 30, 2026"),
            ),
        )

        composeTestRule.onNodeWithText("Test prep · Jul 6, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("Run plan · Jul 3, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("Jun 30, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("Jul 6, 2026").assertDoesNotExist()
        composeTestRule.onNodeWithText("Jul 3, 2026").assertDoesNotExist()
    }

    @Test
    fun distinctPlansWithTheSameCopiedNameStillShowLabels() {
        setContent(
            expanded = false,
            sessions = listOf(
                logSessionFixture(false).copy(planId = 1, planName = "Test prep"),
                logSessionFixture(false).copy(sessionId = 8, dateDisplay = "Jul 3, 2026", planId = 2, planName = "Test prep"),
            ),
        )

        composeTestRule.onNodeWithText("Test prep · Jul 6, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test prep · Jul 3, 2026").assertIsDisplayed()
    }

    @Test
    fun tappingTheHeaderTogglesTheSession() {
        val toggled = mutableListOf<Long>()
        setContent(expanded = false, onToggle = { toggled += it })

        val header = composeTestRule.onNodeWithText("DAY A · LOWER")
        assertEquals(
            "Collapsed",
            header.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        header.performClick()
        assertEquals(listOf(7L), toggled)
    }

    @Test
    fun shareIsASiblingActionThatNeverTogglesTheRow() {
        val toggled = mutableListOf<Long>()
        val shared = mutableListOf<Long>()
        setContent(expanded = true, onToggle = { toggled += it }, onShare = { shared += it })

        assertEquals(
            "Expanded",
            composeTestRule.onNodeWithText("DAY A · LOWER")
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        composeTestRule.onNodeWithText("Share").performClick()
        assertEquals(listOf(7L), shared)
        assertEquals(emptyList<Long>(), toggled)
    }

    @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    @Test
    fun fullHistoryShowsTheFirstSessionAboveTheFoldWithTrendsCollapsed() {
        val history = auditHistoryFixture()
        setContent(false, sessions = history.sessions, journal = history.journal, withNavigation = true)

        composeTestRule.onNodeWithText(history.sessions.first().dateDisplay).assertIsDisplayed()
        assertEquals(
            "Collapsed",
            composeTestRule.onNodeWithText("Trends and calendar").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.StateDescription),
        )
        composeTestRule.onNodeWithText("Weekly volume").assertDoesNotExist()
        composeTestRule.onNodeWithText("November 2026").assertDoesNotExist()
    }

    @Test
    fun disclosureShowsWeeklyTotalsAndTheCalendarAndCanCollapseAgain() {
        val history = auditHistoryFixture()
        setContent(false, sessions = history.sessions, journal = history.journal)

        composeTestRule.onNodeWithText("Trends and calendar").performClick()
        composeTestRule.onNodeWithText("Weekly volume").assertIsDisplayed()
        composeTestRule.onNodeWithText("12,400 lb").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("November 2026"))
        composeTestRule.onNodeWithText("November 2026").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Previous month").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Next month").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Trends and calendar"))
        composeTestRule.onNodeWithText("Trends and calendar").performClick()
        composeTestRule.onNodeWithText("Weekly volume").assertDoesNotExist()
        composeTestRule.onNodeWithText("November 2026").assertDoesNotExist()
    }

    @Test
    fun disclosureSurvivesSavedInstanceStateRestoration() {
        val history = auditHistoryFixture()
        val restoration = StateRestorationTester(composeTestRule)
        setContent(false, sessions = history.sessions, journal = history.journal, restoration = restoration)
        composeTestRule.onNodeWithText("Trends and calendar").performClick()

        restoration.emulateSavedInstanceStateRestore()

        assertEquals(
            "Expanded",
            composeTestRule.onNodeWithText("Trends and calendar").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.StateDescription),
        )
        composeTestRule.onNodeWithText("Weekly volume").assertIsDisplayed()
    }

    @Test
    fun calendarTapScrollsPastExpandedTrendsAndHealthDecisionToItsSession() {
        val history = auditHistoryFixture()
        val cardio = logSessionFixture(false).copy(
            sessionId = history.sessions.first().sessionId, cardioId = history.sessions.first().sessionId,
            dayTitle = "Easy run", dateDisplay = "Latest cardio", cardioSummary = "Road run", cardioDuration = "20:00",
        )
        val sessions = listOf(cardio) + history.sessions + List(20) { i ->
            logSessionFixture(false).copy(sessionId = 100L + i, dateDisplay = "Older session $i")
        }
        setContent(
            false, sessions = sessions, journal = history.journal,
            health = HealthSectionUi(
                available = true, publishing = true,
                backfill = BackfillOfferUi(LogScreenBuilder.backfillLabel(sessions.size, false), true),
                bodyweightPrompt = BodyweightPromptUi("185", "190"),
            ),
        )
        composeTestRule.onNodeWithText("Trends and calendar").performScrollTo().performClick()
        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription("November 3, today, day A, 1 session"))
        composeTestRule.onNodeWithContentDescription("November 3, today, day A, 1 session").performClick()
        composeTestRule.waitForIdle()

        val session = composeTestRule.onNodeWithText(history.sessions.first().dateDisplay).assertIsDisplayed()
        val listTop = composeTestRule.onNode(hasScrollAction()).getUnclippedBoundsInRoot().top
        assertTrue(session.getUnclippedBoundsInRoot().top - listTop < 96.dp)
    }

    @Test
    fun trajectorySemanticsDescribeBothEndpointsWithDatesValuesUnitsAndGoal() {
        val history = auditHistoryFixture()
        setContent(false, sessions = history.sessions, journal = history.journal)

        composeTestRule.onNodeWithContentDescription(
            "Barbell Back Squat top sets: Oct 19, 210 lb; Nov 3, 235 lb. Goal 235 lb.",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Oct 19 to Nov 3").assertIsDisplayed()
    }

    @Test
    fun aSingleTopSetExplainsTheMissingTrendAndLabelsTheLatestValue() {
        val journal = auditHistoryFixture().journal.let { journal ->
            journal.copy(trajectories = journal.trajectories.map { card -> card.copy(points = card.points.takeLast(1)) })
        }
        setContent(false, journal = journal)

        composeTestRule.onNodeWithText("Barbell Back Squat top set").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not enough top sets yet to show a trend.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Latest 235 lb").assertIsDisplayed()
    }

    @Test
    fun noTopSetsExplainTheMissingTrendWithoutALatestNumber() {
        val journal = auditHistoryFixture().journal.let { journal ->
            journal.copy(trajectories = journal.trajectories.map { card -> card.copy(points = emptyList()) })
        }
        setContent(false, journal = journal)

        composeTestRule.onNodeWithText("Barbell Back Squat top set").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not enough top sets yet to show a trend.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Latest 235 lb").assertDoesNotExist()
    }

    @Test
    fun verifiedEmptyAndUnavailableWeeksNeverDisplayZero() {
        setContent(false, journal = JournalUiState(volume = VolumeChart(listOf(
            VolumeWeek(LocalDate.of(2026, 7, 13), 0.0, WeightUnit.LB, trained = false),
            VolumeWeek(LocalDate.of(2026, 7, 6), null, WeightUnit.LB, trained = false),
        ))))
        composeTestRule.onNodeWithText("Trends and calendar").performClick()

        composeTestRule.onNodeWithText("No sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Volume unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText("0 lb").assertDoesNotExist()
    }

    @Test
    fun expandedSessionWithoutDetailsSaysSo() {
        setContent(true, sessions = listOf(logSessionFixture(true).copy(exerciseGroups = emptyList())))
        composeTestRule.onNodeWithText("No set details saved for this session.").assertIsDisplayed()
    }

    // --- Health Connect status and backfill offer -----------------------------
    //
    // The copy is the feature: "nothing appeared in the Health app" was
    // indistinguishable from "never connected", so these pin the two states'
    // exact words as well as their wiring.

    @Test
    fun aGrantedProviderSaysItIsPublishingInsteadOfAskingToConnect() {
        setContent(expanded = false, health = HealthSectionUi(available = true, publishing = true))

        composeTestRule.onNodeWithText("Connected · publishing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect Health Connect").assertDoesNotExist()
    }

    @Test
    fun aProviderWithoutTheWorkoutWriteGrantOffersToConnect() {
        setContent(expanded = false, health = HealthSectionUi(available = true, publishing = false))

        composeTestRule.onNodeWithText("Connect Health Connect").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connected · publishing").assertDoesNotExist()
    }

    @Test
    fun noProviderShowsNeitherStateAtAll() {
        setContent(expanded = false, health = HealthSectionUi(available = false))

        composeTestRule.onNodeWithText("Connect Health Connect").assertDoesNotExist()
        composeTestRule.onNodeWithText("Connected · publishing").assertDoesNotExist()
    }

    @Test
    fun theBackfillOfferPublishesPastWorkoutsWhenTapped() {
        var taps = 0
        setContent(
            expanded = false,
            health = HealthSectionUi(
                available = true,
                publishing = true,
                backfill = BackfillOfferUi(label = LogScreenBuilder.backfillLabel(12, false), enabled = true),
            ),
            onPublishPast = { taps += 1 },
        )

        composeTestRule.onNodeWithText("Publish 12 past workouts").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun aRunningBackfillCannotBeTappedAgain() {
        var taps = 0
        setContent(
            expanded = false,
            health = HealthSectionUi(
                available = true,
                publishing = true,
                backfill = BackfillOfferUi(label = LogScreenBuilder.backfillLabel(12, true), enabled = false),
            ),
            onPublishPast = { taps += 1 },
        )

        composeTestRule.mainClock.advanceTimeBy(400)
        composeTestRule.onNodeWithText("Publishing 12 workouts to Health Connect…").assertIsDisplayed().assertHasNoClickAction()
        composeTestRule.onNode(androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate,
        )).assertIsDisplayed()
        assertEquals(0, taps)
    }
}
