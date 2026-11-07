package cloud.trotter.log.strength.ui.day

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.model.CardioActivity
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

    @Test fun `a save failure stays visible and History can supply an extra action`() {
        val draft = CardioEntryDraft(CardioActivity.OUTDOOR_RIDE, now - 1_800_000, 1800, measuresEditable = false)
        rule.setContent { AppTheme {
            CardioEntrySheet(draft, "Log past session", { now }, {}, {}, {}, saveFailed = true,
                extraAction = { Text("Extra action") })
        } }
        rule.onNodeWithText("Duration").assertDoesNotExist()
        rule.onNodeWithText("Distance (m)").assertDoesNotExist()
        rule.onNodeWithText("Couldn't save the session.").performScrollTo().assertExists()
        rule.onNodeWithText("Extra action").assertExists()
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
        listOf("Activity", "Date", "Start time", "Duration", "Distance (m)", "Note").forEach {
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
