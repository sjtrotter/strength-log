package cloud.trotter.log.strength.ui.day

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.components.LocalDistanceUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CardioEntrySheetTest {
    @get:Rule val rule = createComposeRule()
    private val now = 1_800_000_000_000L

    @Test fun `log past session is offered before the run starts`() {
        var state by mutableStateOf(workoutSetCardioFixture())
        var opened = 0
        rule.setContent { AppTheme {
            WorkoutSetHost(state, workoutActions().copy(onOpenPastCardio = { opened++ }))
        } }
        rule.onNodeWithText("Log past session").performClick()
        rule.runOnIdle { assertEquals(1, opened); state = workoutSetCardioRunningFixture() }
        rule.onNodeWithText("Log past session").assertDoesNotExist()
        rule.runOnIdle { state = workoutSetCardioBlockedByTimedRepFixture() }
        rule.onNodeWithText("Log past session").assertDoesNotExist()
        rule.runOnIdle { state = workoutSetCardioDoneFixture() }
        rule.onNodeWithText("Log past session").assertDoesNotExist()
    }

    @Test fun `the cardio utilities offer the entry only before a run starts`() {
        var cardio by mutableStateOf(cardioFixture())
        var opened = 0
        rule.setContent { AppTheme {
            WorkoutCardioSheet(cardio, "A", workoutActions().copy(onOpenPastCardio = { opened++ }), {})
        } }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Log past session").performClick()
        rule.runOnIdle { assertEquals(1, opened); cardio = runningCardioFixture() }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Log past session").assertDoesNotExist()
    }

    @Test fun `an awaiting timed rep count hides both past session entry points`() {
        val base = workoutSetCardioBlockedByTimedRepFixture()
        var state by mutableStateOf(base.copy(executionBusy = false, timedRep = base.timedRep?.let {
            it.copy(event = it.event.copy(awaitingCount = true, sheetDismissed = true), remainingSeconds = 0)
        }))
        val actions = workoutActions().copy(onOpenCardioSets = {
            state = state.copy(openSheet = OpenSheet("A", OpenSheet.Kind.CARDIO_SETS, 0, 0))
        })
        rule.setContent { AppTheme { WorkoutSetHost(state, actions) } }
        rule.onNodeWithText("Log past session").assertDoesNotExist()
        rule.onNodeWithText("All sets").performClick()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Log past session").assertDoesNotExist()
        rule.runOnIdle { state = state.copy(timedRep = null, openSheet = null) }
        rule.onNodeWithText("Log past session").assertExists()
    }

    @Test fun `the entry sheet saves only a valid draft`() {
        var draft by mutableStateOf(CardioEntryDraft(CardioActivity.ROAD_RUN, now - 2_472_000, 0))
        var saved = 0
        rule.setContent { AppTheme {
            CardioEntrySheet(draft, "Log past session", { now }, { draft = it }, { saved++ }, {})
        } }
        rule.onNodeWithText("Distance (mi)").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Duration").performScrollTo().performTextReplacement("40:12")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Duration").performTextReplacement("0:00")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Duration").performTextReplacement("40:12")
        rule.runOnIdle { draft = draft.copy(startedAt = now) }
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Ends after now").performScrollTo().assertExists()
        rule.runOnIdle { draft = draft.copy(startedAt = now - 2_472_000) }
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, saved); assertEquals(2412, draft.seconds) }
    }

    @Test fun `a past road run accepts kilometers and saves meters`() {
        assertDistanceEntry(DistanceUnit.KM, "7.1", 7100)
    }

    @Test fun `a past road run accepts miles and saves meters`() {
        assertDistanceEntry(DistanceUnit.MI, "4.4", 7081)
    }

    private fun assertDistanceEntry(unit: DistanceUnit, text: String, meters: Int) {
        var draft by mutableStateOf(CardioEntryDraft(CardioActivity.ROAD_RUN, now - 2_472_000, 2412))
        var saved: CardioEntryDraft? = null
        rule.setContent { CompositionLocalProvider(LocalDistanceUnit provides unit) { AppTheme {
            CardioEntrySheet(draft, "Log past session", { now }, { draft = it }, { saved = draft }, {})
        } } }
        val field = rule.onNodeWithText("Distance (${unit.symbol})")
        field.performScrollTo().performTextReplacement(text.substringBefore('.') + ".")
        field.assertTextContains(text.substringBefore('.') + ".")
        field.performTextReplacement(text)
        field.assertTextContains(text)
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(meters.toString(), saved?.metresText)
            assertEquals(meters, saved?.metres)
        }
    }

    @Test fun `distance limits use meters and blank distance stays optional`() {
        var draft by mutableStateOf(CardioEntryDraft(CardioActivity.ROAD_RUN, now - 2_472_000, 2412))
        rule.setContent { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.KM) { AppTheme {
            CardioEntrySheet(draft, "Log past session", { now }, { draft = it }, {}, {})
        } } }
        val field = rule.onNodeWithText("Distance (km)").performScrollTo()
        listOf("0", "-1", ".", "NaN", "Infinity", "200.01").forEach { text ->
            field.performTextReplacement(text)
            field.assertTextContains(text)
            rule.onNodeWithText("Save").assertIsNotEnabled()
        }
        field.performTextReplacement("200")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { assertEquals(200000, draft.metres) }
        field.performTextReplacement("")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.runOnIdle { assertEquals("", draft.metresText) }
    }

    @Test fun `activity changes display the same meters in the activity unit`() {
        var draft by mutableStateOf(CardioEntryDraft(CardioActivity.ROAD_RUN, now - 2_472_000, 2412, "7100"))
        rule.setContent { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.KM) { AppTheme {
            CardioEntrySheet(draft, "Log past session", { now }, { draft = it }, {}, {})
        } } }
        rule.onNodeWithText("Distance (km)").performScrollTo().assertTextContains("7.1")
        rule.onNodeWithContentDescription("Increase distance").performClick()
        rule.onNodeWithText("Distance (km)").assertTextContains("7.15")
        rule.runOnIdle { assertEquals(7150, draft.metres) }
        rule.onNodeWithText("Activity").performScrollTo().performClick()
        rule.onNodeWithText("Track run").performScrollTo().performClick()
        rule.onNodeWithText("Distance (m)").performScrollTo().assertTextContains("7150")
        rule.onNodeWithContentDescription("Increase distance").performClick()
        rule.runOnIdle { assertEquals(7151, draft.metres) }
        rule.onNodeWithText("Distance (m)").performTextReplacement("7.1")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Distance (m)").performTextReplacement("7100")
        rule.onNodeWithText("Activity").performScrollTo().performClick()
        rule.onNodeWithText("Rower").performScrollTo().performClick()
        rule.onNodeWithText("Distance (m)").performScrollTo().assertTextContains("7100")
        rule.onNodeWithText("Activity").performScrollTo().performClick()
        rule.onNodeWithText("Road run").performScrollTo().performClick()
        rule.onNodeWithText("Distance (km)").performScrollTo().assertTextContains("7.1")
        rule.runOnIdle { assertEquals(7100, draft.metres) }
    }

    @Test fun `a save failure stays visible and History can supply an extra action`() {
        val draft = CardioEntryDraft(CardioActivity.OUTDOOR_RIDE, now - 1_800_000, 1800, measuresEditable = false)
        rule.setContent { AppTheme {
            CardioEntrySheet(draft, "Log past session", { now }, {}, {}, {}, saveFailed = true,
                extraAction = { Text("Extra action") })
        } }
        rule.onNodeWithText("Duration").assertDoesNotExist()
        rule.onNodeWithText("Distance (mi)").assertDoesNotExist()
        rule.onNodeWithText("Couldn't save the session.").performScrollTo().assertExists()
        rule.onNodeWithText("Extra action").assertExists()
    }

    @Test fun `a start time collision keeps the entry editable`() {
        val draft = CardioEntryDraft(CardioActivity.ROAD_RUN, now - 2_472_000, 2412, "7100", "Easy")
        rule.setContent { AppTheme {
            DayScreen(workoutSetCardioFixture(), workoutActions(), DayEditUiState(), workoutEditActions(),
                pastCardio = draft, pastCardioSaveFailed = true, pastCardioCollision = true, nowMillis = { now })
        } }
        rule.onNodeWithText("Start time collision").performScrollTo().assertExists()
        rule.onNodeWithText("Couldn't save the session.").assertDoesNotExist()
        rule.onNodeWithText("Start time").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun `saving disables entry fields and duplicate saves until a failure allows editing`() {
        val draft = CardioEntryDraft(CardioActivity.ROAD_RUN, now - 2_472_000, 2412, "7100", "Easy")
        var saving by mutableStateOf(false)
        rule.setContent { AppTheme {
            DayScreen(workoutSetCardioFixture(), workoutActions().copy(onSavePastCardio = { saving = true }),
                DayEditUiState(), workoutEditActions(), pastCardio = draft, pastCardioSaving = saving,
                pastCardioSaveFailed = !saving, nowMillis = { now })
        } }
        rule.onNodeWithText("Save").performClick().assertIsNotEnabled()
        listOf("Activity", "Date", "Start time", "Duration", "Distance (mi)", "Note").forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsNotEnabled()
        }
        listOf("Increase duration", "Decrease duration", "Increase distance", "Decrease distance").forEach {
            rule.onNodeWithContentDescription(it).assertIsNotEnabled()
        }
        rule.onNodeWithText("Cancel").assertDoesNotExist()
        rule.runOnIdle { saving = false }
        rule.onNodeWithText("Note").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("Couldn't save the session.").performScrollTo().assertExists()
        rule.onNodeWithText("Save").assertIsEnabled()
    }
}
