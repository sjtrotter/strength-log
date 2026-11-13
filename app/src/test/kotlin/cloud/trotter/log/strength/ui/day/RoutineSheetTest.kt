package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.domain.routine.Move
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1200dp")
class RoutineSheetTest {
    @get:Rule val rule = createComposeRule()
    private val sheet = RoutineSheetState("run", listOf(Move.LEG_SWINGS, Move.QUAD_STRETCH), emptySet(), 300, null, "run")

    @Test fun `checklist rows expose checkbox role size doses and toggle`() {
        var toggled: Move? = null
        rule.setContent { AppTheme {
            RoutineSheet(RoutineKind.WARM_UP, sheet, 0, { 0 }, { toggled = it }, {}, {}, {}, {})
        } }
        listOf("Leg swings", "Standing quad stretch").forEach { name ->
            rule.onNodeWithText(name).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithText("Leg swings").performClick()
        rule.runOnIdle { assertEquals(Move.LEG_SWINGS, toggled) }
        rule.onNodeWithText("10 each side", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("0:30 each side", useUnmergedTree = true).assertExists()
    }

    @Test fun `Done and Skip close the sheet`() {
        var closed = 0
        rule.setContent { AppTheme {
            RoutineSheet(RoutineKind.WARM_UP, sheet, 0, { 0 }, {}, {}, {}, { closed++ }, {})
        } }
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Skip").performClick()
        rule.runOnIdle { assertEquals(2, closed) }
    }

    @Test fun `cardio warmup offers Start and Not yet`() {
        var started = 0
        var closed = 0
        rule.setContent { AppTheme {
            RoutineSheet(RoutineKind.WARM_UP, sheet.copy(startsCardio = true, runId = null), 0, { 0 },
                {}, {}, {}, { closed++ }, { started++ })
        } }
        rule.onNodeWithText("Skip").assertDoesNotExist()
        rule.onNodeWithText("Start").performClick()
        rule.onNodeWithText("Not yet").performClick()
        rule.runOnIdle { assertEquals(1, started); assertEquals(1, closed) }
    }

    @Test fun `Start timer forwards the callback`() {
        var started = 0
        rule.setContent { AppTheme {
            RoutineSheet(RoutineKind.COOL_DOWN, sheet, 0, { 0 }, {}, { started++ }, {}, {}, {})
        } }
        rule.onNodeWithText("Start timer").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, started) }
    }

    @Test fun `running timer shows the remaining time and Stop callback`() {
        var stopped = 0
        rule.setContent { AppTheme {
            RoutineSheet(RoutineKind.WARM_UP, sheet.copy(timerStartedAt = 0), 0, { 108_000 },
                {}, {}, { stopped++ }, {}, {})
        } }
        rule.onNodeWithTag("routine-timer-3:12", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("3 minutes 12 seconds left").assertExists()
        rule.onNodeWithText("Stop timer").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, stopped) }
    }
}
