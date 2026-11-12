package cloud.trotter.log.strength.ui.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.audit.AuditDestination
import cloud.trotter.log.strength.audit.auditHistoryFixture
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.components.LocalDistanceUnit
import cloud.trotter.log.strength.ui.navigation.TopLevelDestination
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.day.CardioEntryDraft
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
        distanceUnit: DistanceUnit = DistanceUnit.MI,
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
                CompositionLocalProvider(LocalDistanceUnit provides distanceUnit) {
                    if (withNavigation) AuditDestination(TopLevelDestination.LOG, screen) else screen()
                }
            }
        }
        if (restoration == null) composeTestRule.setContent(content) else restoration.setContent(content)
    }

    @Test
    fun weeklyDistanceShowsMilesMissingSessionsAndRowingMeters() {
        setContent(false, journal = JournalUiState(weeklyDistance = listOf(
            WeeklyDistance(CardioFamily.RUN, 13_519, 1),
            WeeklyDistance(CardioFamily.ROW, 2000, 0),
            WeeklyDistance(CardioFamily.WALK, 0, 2),
        )), distanceUnit = DistanceUnit.MI)

        composeTestRule.onNodeWithText("Distance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Running this week").assertIsDisplayed()
        composeTestRule.onNodeWithText("8.4 mi").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 session without distance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rowing this week").assertIsDisplayed()
        composeTestRule.onNodeWithText("2,000 m").assertIsDisplayed()
        composeTestRule.onNodeWithText("Walking this week").assertIsDisplayed()
        composeTestRule.onNodeWithText("Distance not entered").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 sessions without distance").assertIsDisplayed()
        assertEquals(
            "Collapsed",
            composeTestRule.onNodeWithText("Trends and calendar").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.StateDescription),
        )
    }

    @Test
    fun weeklyDistanceUsesTheKilometerSettingForActivityGroups() {
        setContent(false, journal = JournalUiState(weeklyDistance = listOf(
            WeeklyDistance(CardioFamily.RUN, 5000, 0),
            WeeklyDistance(CardioFamily.RIDE, 20_000, 0),
            WeeklyDistance(CardioFamily.ELLIPTICAL, 1000, 0),
        )), distanceUnit = DistanceUnit.KM)

        composeTestRule.onNodeWithText("Running this week").assertIsDisplayed()
        composeTestRule.onNodeWithText("5 km").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cycling this week").assertIsDisplayed()
        composeTestRule.onNodeWithText("20 km").assertIsDisplayed()
        composeTestRule.onNodeWithText("Elliptical this week").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 km").assertIsDisplayed()
        composeTestRule.onNodeWithText("session without distance", substring = true).assertDoesNotExist()
    }

    @Test
    fun weeklyDistanceCapsLargeTotalsBeforeFormatting() {
        setContent(false, journal = JournalUiState(weeklyDistance = listOf(
            WeeklyDistance(CardioFamily.ROW, Long.MAX_VALUE, 0),
        )))

        composeTestRule.onNodeWithText("2,147,483,647 m").assertIsDisplayed()
    }

    @Test fun historyShowsLegsOnlyForASuppliedReadingAtTheTarget() {
        val session = CardioSessionEntity(id = 8, dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Road run", startedAt = 1000, completedAt = 1_691_000, seconds = 1690,
            stepsCompleted = 1, activity = CardioActivity.ROAD_RUN.name)
        val result = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(1500)),
            CardioSetStatus.FINISHED, 1690, 5600, DistanceSource.MANUAL, metresAtEnd = 5000,
            secondsAtEnd = 1500, readingSource = ReadingSource.GPS)
        setContent(false, distanceUnit = DistanceUnit.KM, sessions = listOf(
            LogScreenBuilder.cardioItem(session, listOf(result)),
            LogScreenBuilder.cardioItem(session.copy(id = 9), listOf(result.copy(metresAtEnd = null, readingSource = null))),
        ))
        composeTestRule.onAllNodesWithText("5.00 km at 25:00", useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("3:10, 0.60 km past the target", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun historyLabelsEachIntervalsMeasuredLegs() {
        val session = CardioSessionEntity(id = 8, dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Road run", startedAt = 1000, completedAt = 346_000, seconds = 345,
            stepsCompleted = 2, activity = CardioActivity.ROAD_RUN.name)
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(500))
        val results = listOf(
            CardioSetResult(spec, CardioSetStatus.FINISHED, 165, 550, DistanceSource.MANUAL,
                secondsAtEnd = 150, readingSource = ReadingSource.TYPED),
            CardioSetResult(spec, CardioSetStatus.FINISHED, 180, 600, DistanceSource.MANUAL,
                secondsAtEnd = 160, readingSource = ReadingSource.TYPED),
        )
        setContent(false, distanceUnit = DistanceUnit.KM, sessions = listOf(LogScreenBuilder.cardioItem(session, results)))

        val labelsAndLegs = listOf("Interval 1", "2:30 at 0.5 km", "0:15, 0.05 km past the target",
            "Interval 2", "2:40 at 0.5 km", "0:20, 0.10 km past the target")
        val positions = labelsAndLegs.map {
            composeTestRule.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(positions.zipWithNext().all { (first, second) -> first < second })
    }

    @Test fun roadHistoryTotalsUseMiles() {
        val session = CardioSessionEntity(id = 8, dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Road run", startedAt = 1000, completedAt = 1_801_000, seconds = 1800,
            stepsCompleted = 2, activity = CardioActivity.ROAD_RUN.name)
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(900))
        val results = listOf(2400, 2428).map { metres ->
            CardioSetResult(spec, CardioSetStatus.FINISHED, 900, metres, DistanceSource.MANUAL)
        }
        setContent(false, sessions = listOf(LogScreenBuilder.cardioItem(session, results)))
        composeTestRule.onNodeWithText("Distance 3 mi", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a cardio card shows its note and opens its editor`() {
        val start = 1_783_512_000_000L
        val session = CardioSessionEntity(
            id = 8, dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Road run",
            startedAt = start, completedAt = start + 2_413_000, seconds = 2413,
            stepsCompleted = 1, activity = CardioActivity.ROAD_RUN.name, note = "Easy",
        )
        val item = LogScreenBuilder.cardioItem(session, listOf(ManualCardio.result(2413, 7100)))
        val state = mutableStateOf(LogUiState(sessions = listOf(item)))
        val edited = mutableListOf<Long>()
        composeTestRule.setContent { AppTheme {
            LogScreen(state.value, LogActions(
                onToggleExpanded = {}, onPageCalendar = {}, onConnectHealth = {}, onPublishPastWorkouts = {},
                onApplyBodyweight = {}, onDismissBodyweight = {}, onShare = {},
                onStartSession = {}, onSetUpProgram = {},
                onEditCardio = { id ->
                    edited += id
                    state.value = state.value.copy(cardioEdit = CardioEntryDraft(
                        CardioActivity.ROAD_RUN, start, 2413, "7100", "Easy",
                    ))
                },
            ))
        } }

        composeTestRule.onNodeWithText("Easy", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit").performScrollTo().performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(8L), edited) }
        composeTestRule.onNodeWithText("Edit session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete session").assertDoesNotExist()
        composeTestRule.onNodeWithText("Date").performScrollTo().assertHasNoClickAction()
        composeTestRule.onNodeWithText("Start time").performScrollTo().assertHasNoClickAction()
        val shownStart = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
        composeTestRule.onNodeWithText(shownStart.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)))
            .assertHasNoClickAction()
        composeTestRule.onNodeWithText(shownStart.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)))
            .assertHasNoClickAction()
    }

    @Test
    fun `a cardio editor forwards changes save and dismissal`() {
        val state = mutableStateOf(LogUiState(cardioEdit = CardioEntryDraft(
            CardioActivity.ROAD_RUN, 1_783_512_000_000L, 2413, note = "Easy",
        )))
        var saved = 0
        var dismissed = 0
        composeTestRule.setContent { AppTheme {
            LogScreen(state.value, LogActions(
                onToggleExpanded = {}, onPageCalendar = {}, onConnectHealth = {}, onPublishPastWorkouts = {},
                onApplyBodyweight = {}, onDismissBodyweight = {}, onShare = {},
                onStartSession = {}, onSetUpProgram = {},
                onCardioEditChange = { state.value = state.value.copy(cardioEdit = it) },
                onSaveCardioEdit = { saved++ },
                onDismissCardioEdit = { dismissed++ },
            ))
        } }

        composeTestRule.onNodeWithText("Duration").performScrollTo().performTextReplacement("40:12")
        composeTestRule.onNodeWithText("Note").performScrollTo().performTextReplacement("Hilly")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.runOnIdle {
            assertEquals(2412, state.value.cardioEdit!!.seconds)
            assertEquals("Hilly", state.value.cardioEdit!!.note)
            assertEquals(1, saved)
        }
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun `a History interval editor shows its elapsed completion error and disables Save`() {
        val now = System.currentTimeMillis()
        val draft = CardioEntryDraft(CardioActivity.ROAD_RUN, now - 1_800_000, 1800, measuresEditable = false)
        val state = mutableStateOf(LogUiState(cardioEdit = draft, cardioEditCompletedAt = now + 1_800_000))
        composeTestRule.setContent { AppTheme {
            LogScreen(state.value, LogActions(
                onToggleExpanded = {}, onPageCalendar = {}, onConnectHealth = {}, onPublishPastWorkouts = {},
                onApplyBodyweight = {}, onDismissBodyweight = {}, onShare = {},
                onStartSession = {}, onSetUpProgram = {},
            ))
        } }

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Ends after now").performScrollTo().assertIsDisplayed()
        composeTestRule.runOnIdle { state.value = state.value.copy(cardioEditCompletedAt = now - 1_000) }
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
        composeTestRule.onNodeWithText("Ends after now").assertDoesNotExist()
    }

    @Test
    fun expandedHistoryShowsLiftNotesAfterExerciseGroupsAndKeepsTheSessionNote() {
        val session = mutableStateOf(logSessionFixture(true).copy(
            note = "Felt steady",
            exerciseGroups = listOf(
                SessionExerciseGroup("Back Squat", listOf(SessionSetSummary("TOP", "225×5", done = true))),
                SessionExerciseGroup("Bench", listOf(SessionSetSummary("1", "135×8", done = true))),
            ),
            liftNotes = listOf(SessionLiftNote("Back Squat", "Belt on top"), SessionLiftNote("Bench", "Bench 2")),
        ))
        composeTestRule.setContent { AppTheme {
            LogScreen(LogUiState(sessions = listOf(session.value)), LogActions(
                onToggleExpanded = { session.value = session.value.copy(expanded = !session.value.expanded) },
                onPageCalendar = {}, onConnectHealth = {}, onPublishPastWorkouts = {},
                onApplyBodyweight = {}, onDismissBodyweight = {}, onShare = {},
                onStartSession = {}, onSetUpProgram = {},
            ))
        } }
        composeTestRule.onNodeWithText("Felt steady").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lift notes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back Squat: Belt on top").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bench: Bench 2").assertIsDisplayed()
        val setsBounds = composeTestRule.onNodeWithText("1: 135×8").getUnclippedBoundsInRoot()
        val headingBounds = composeTestRule.onNodeWithText("Lift notes").getUnclippedBoundsInRoot()
        val firstNoteBounds = composeTestRule.onNodeWithText("Back Squat: Belt on top").getUnclippedBoundsInRoot()
        val secondNoteBounds = composeTestRule.onNodeWithText("Bench: Bench 2").getUnclippedBoundsInRoot()
        assertTrue(headingBounds.top >= setsBounds.bottom)
        assertTrue(firstNoteBounds.top >= headingBounds.bottom)
        assertTrue(secondNoteBounds.top >= firstNoteBounds.bottom)
        composeTestRule.runOnIdle { session.value = session.value.copy(expanded = false) }
        composeTestRule.onNodeWithText("Lift notes").assertDoesNotExist()
        composeTestRule.onNodeWithText("Back Squat: Belt on top").assertDoesNotExist()
        composeTestRule.onNodeWithText("Bench: Bench 2").assertDoesNotExist()
        composeTestRule.onNodeWithText("Felt steady").assertDoesNotExist()
    }

    @Test
    fun expandedHistoryOmitsLiftNotesBlockWhenEmpty() {
        setContent(true, sessions = listOf(logSessionFixture(true).copy(note = "Felt steady")))

        composeTestRule.onNodeWithText("Felt steady").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lift notes").assertDoesNotExist()
    }

    @Test
    fun expandedHistoryShowsOnlyDoneRowsAndEditShowsEveryRow() {
        val session = mutableStateOf(logSessionFixture(true).copy(setCount = 1, exerciseGroups = listOf(
            SessionExerciseGroup("Back Squat", listOf(
                SessionSetSummary("1", "100×8", id = 1, weightLb = 100.0, reps = 8, done = true),
                SessionSetSummary("2", "90×10", id = 2, weightLb = 90.0, reps = 10, done = false),
            )),
            SessionExerciseGroup("Bench", listOf(
                SessionSetSummary("1", "60×12", id = 3, weightLb = 60.0, reps = 12, done = false),
            )),
        )))
        composeTestRule.setContent { AppTheme {
            LogScreen(LogUiState(sessions = listOf(session.value)), LogActions(
                onToggleExpanded = {}, onPageCalendar = {}, onConnectHealth = {}, onPublishPastWorkouts = {},
                onApplyBodyweight = {}, onDismissBodyweight = {}, onShare = {},
                onToggleEdit = { session.value = session.value.copy(editing = !session.value.editing) },
                onStartSession = {}, onSetUpProgram = {},
            ))
        } }

        composeTestRule.onNodeWithText("1 set").assertIsDisplayed()
        composeTestRule.onNodeWithText("1: 100×8").assertIsDisplayed()
        composeTestRule.onNodeWithText("90×10", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Bench").assertDoesNotExist()

        composeTestRule.onNodeWithText("Edit").performScrollTo().performClick()
        composeTestRule.onNodeWithText("100").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("90").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Bench").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("60").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText("Done").performScrollTo().performClick()
        composeTestRule.onNodeWithText("1: 100×8").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("90×10", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Bench").assertDoesNotExist()
    }

    @Test
    fun editingUsesLabeledWeightAndRepsFieldsAndRejectsBlankWeight() {
        val weights = mutableListOf<Double>()
        val reps = mutableListOf<Int>()
        val session = logSessionFixture(true).copy(editing = true, exerciseGroups = listOf(
            SessionExerciseGroup("Back Squat", listOf(SessionSetSummary("TOP", "225×5", id = 1,
                weightLb = 225.0, reps = 5, done = true))),
        ))
        composeTestRule.setContent { AppTheme {
            LogScreen(LogUiState(sessions = listOf(session)), LogActions(
                onToggleExpanded = {}, onPageCalendar = {}, onConnectHealth = {}, onPublishPastWorkouts = {},
                onApplyBodyweight = {}, onDismissBodyweight = {}, onShare = {},
                onWeightChange = { _, id, value -> assertEquals(1L, id); weights += value },
                onRepsChange = { _, id, value -> assertEquals(1L, id); reps += value },
                onStartSession = {}, onSetUpProgram = {},
            ))
        } }
        composeTestRule.onNodeWithText("Weight (lb)").performScrollTo().performClick().performTextReplacement("232")
        composeTestRule.onNodeWithText("Weight (lb)").performImeAction()
        composeTestRule.onNodeWithText("Reps").performClick().performTextReplacement("8")
        composeTestRule.onNodeWithText("Reps").performImeAction()
        composeTestRule.onNodeWithText("Weight (lb)").performClick().performTextReplacement("")
        composeTestRule.onNodeWithText("Weight (lb)").performImeAction()
        assertEquals(listOf(230.0), weights)
        assertEquals(listOf(8), reps)
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
    fun calendarTapScrollsToItsSessionWithDistanceRows() {
        val history = auditHistoryFixture()
        val cardio = logSessionFixture(false).copy(
            sessionId = history.sessions.first().sessionId, cardioId = history.sessions.first().sessionId,
            dayTitle = "Easy run", dateDisplay = "Latest cardio", cardioSummary = "Road run", cardioDuration = "20:00",
        )
        val sessions = listOf(cardio) + history.sessions + List(20) { i ->
            logSessionFixture(false).copy(sessionId = 100L + i, dateDisplay = "Older session $i")
        }
        val journal = history.journal.copy(weeklyDistance = listOf(
            WeeklyDistance(CardioFamily.RUN, 13_519, 1),
            WeeklyDistance(CardioFamily.ROW, 2000, 0),
        ))
        setContent(
            false, sessions = sessions, journal = journal,
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
    fun trajectoryWithoutAWeightGoalShowsEndpointsWithoutAGoalReference() {
        val journal = auditHistoryFixture().journal.let { journal ->
            journal.copy(trajectories = journal.trajectories.map { card ->
                card.copy(goalValue = null, goalLabel = null, goalMet = false)
            })
        }
        setContent(false, journal = journal)

        composeTestRule.onNodeWithContentDescription(
            "Barbell Back Squat top sets: Oct 19, 210 lb; Nov 3, 235 lb.",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Latest 235 lb").assertIsDisplayed()
        composeTestRule.onNodeWithText("Goal", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Goal met").assertDoesNotExist()
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
