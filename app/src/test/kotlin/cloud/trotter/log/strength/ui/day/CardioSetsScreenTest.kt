package cloud.trotter.log.strength.ui.day

import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.dayAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CardioSetsScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun block() = CardioBlock(CardioActivity.TRACK_RUN, listOf(
        CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105)),
        CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)),
    ))

    private fun run(prescription: CardioBlock = block()): CardioRun {
        val json = CardioBlockJson.encode(prescription)
        return CardioRun(1L, "Plan", "D", 0, json, json, false, null,
            startedAt = 1_000, wallStart = 1_000, elapsedStart = 1_000, bootCount = 1)
    }

    @Test fun distanceShowsStaticTargetAndElapsedUntilReachedTap() {
        var reached = 0
        var delivered: CardioSetAction? = null
        val run = run()
        val cardio = CardioCardState("Track run", "", false, block = run.block, run = run, elapsedSeconds = 127)
        rule.setContent { AppTheme {
            CardioPage(cardio, "D", dayAccent(0), pagerActions().copy(onReachCardioTarget = { delivered = it; reached++ }), false, {})
        } }
        rule.onNodeWithText("Target").assertExists()
        rule.onNodeWithText("400 m").assertExists()
        rule.onNodeWithText("Elapsed 2:07").assertExists()
        rule.onNodeWithText("Stop").assertExists()
        rule.onNodeWithText("Reached 400 m").performClick()
        rule.runOnIdle {
            assertEquals(1, reached)
            assertEquals(run.action(), delivered)
        }
    }

    @Test fun entryIsBlankWithoutKeyboardFocusAndOnlyPositiveMetresOrBlankEnableDone() {
        var run by mutableStateOf(run().copy(wallStart = null, elapsedStart = null, bootCount = null,
            results = listOf(SavedCardioResult("SHORTENED", 23)),
            drafts = listOf(CardioDistanceDraft(0, stopped = true))))
        var saved = 0
        var delivered: CardioSetAction? = null
        val actions = pagerActions().copy(
            onCardioDistanceChange = { text -> run = run.copy(drafts = listOf(run.drafts.single().copy(text = text))) },
            onSaveCardioDistance = { delivered = it; saved++ },
        )
        rule.setContent { AppTheme { CardioDistanceSheet(run, 0, actions) } }
        rule.onNodeWithText("Set stopped").assertExists()
        rule.onNodeWithText("Track run · Set 1").assertExists()
        rule.onNodeWithText("Distance (m)").assertIsNotFocused()
        rule.onNodeWithText("Done").assertIsEnabled()
        rule.onNodeWithText("Distance (m)").performTextReplacement("0")
        rule.onNodeWithText("Done").assertIsNotEnabled()
        rule.onNodeWithText("Enter metres greater than 0").assertExists()
        rule.onNodeWithContentDescription("Increase distance").performClick()
        rule.onNodeWithText("Done").assertIsEnabled()
        rule.onNodeWithText("Distance (m)").performTextReplacement("")
        rule.onNodeWithText("Done").performClick()
        rule.runOnIdle {
            assertEquals(1, saved)
            assertEquals(run.action(0), delivered)
        }
    }

    @Test fun allSetsReportsStatusesAndTheMeasuredAimComparison() {
        val run = run().copy(wallStart = null, elapsedStart = null, bootCount = null,
            results = listOf(SavedCardioResult("FINISHED", 127, 400)))
        val cardio = CardioCardState("Track run", "", false, block = run.block, run = run)
        rule.setContent { AppTheme { CardioSetsSheet(cardio, pagerActions()) } }
        rule.onNodeWithText("Set 1, Work, Finished").assertExists()
        rule.onNodeWithText("Finished · 2:07 / 1:45 · Aim missed").assertExists()
        rule.onNodeWithText("Set 2, Recovery, Ready").assertExists()
        rule.onNodeWithText("Add set").assertIsEnabled()
    }

    @Test fun addSetIsDisabledWhenAnotherDayOwnsExecution() {
        val cardio = CardioCardState("Track run", "", false, block = block(), canAddSet = false)
        var additions = 0
        rule.setContent { AppTheme {
            CardioSetsSheet(cardio, pagerActions().copy(onAddCardioSet = { additions++ }))
        } }
        rule.onNodeWithText("Add set").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(0, additions) }
    }

    @Test fun distanceAndRecoveryHoldTheViewAwakeAndDisposalClearsIt() {
        var visible by mutableStateOf(true)
        val distanceRun = run()
        var state by mutableStateOf(pagerFixture().copy(exercises = emptyList(), selectedPageKey = "cardio",
            cardioRunning = true, executionBusy = true,
            cardio = CardioCardState("Track run", "", false, block = distanceRun.block, run = distanceRun)))
        var view: View? = null
        rule.setContent { AppTheme {
            val currentView = LocalView.current
            SideEffect { view = currentView }
            if (visible) DayScreen(state, pagerActions(), DayEditUiState(), pagerEditActions())
        } }
        rule.runOnIdle {
            assertTrue(requireNotNull(view).keepScreenOn)
            val recoveryBlock = CardioBlock(CardioActivity.TRACK_RUN, listOf(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120))))
            val recovery = run(recoveryBlock)
            state = state.copy(cardio = state.cardio?.copy(block = recoveryBlock, run = recovery))
        }
        rule.runOnIdle { assertTrue(requireNotNull(view).keepScreenOn); visible = false }
        rule.runOnIdle { assertFalse(requireNotNull(view).keepScreenOn) }
    }
}
