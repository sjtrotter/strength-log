package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.components.LocalDistanceUnit
import cloud.trotter.log.strength.ui.log.CardioEditGps
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CardioEntrySheetGpsTest {
    @get:Rule val rule = createComposeRule()
    private val now = 1_800_000_000_000L
    private var draft by mutableStateOf(CardioEntryDraft(CardioActivity.ROAD_RUN, now - 2_000_000, 1900, "5700"))
    private fun show() {
        val row = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(1800)), CardioSetStatus.FINISHED,
            1900, 5700, DistanceSource.GPS, 5400, 1800, ReadingSource.GPS)
        var reading by mutableStateOf<CardioReadingInput?>(null)
        rule.setContent { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.MI) { AppTheme {
            CardioEntrySheet(draft, "Edit cardio", { now }, { draft = it }, {}, {}, startEditable = false,
                gps = CardioEditGps(row), reading = reading, onReadingChange = { reading = CardioReadingInput("cardio:1", 0, it, DistanceUnit.MI) })
        } } }
    }
    @Test fun `GPS totals and readings show their source`() {
        show()
        assertEquals(2, rule.onAllNodesWithText("Measured by GPS").fetchSemanticsNodes().size)
        rule.onNodeWithText("Distance at 30:00").performScrollTo().assertTextContains("3.36")
        rule.onNodeWithText("Save").assertIsEnabled()
    }
    @Test fun `a changed total shows the original GPS distance`() {
        show()
        rule.onNodeWithText("Distance (mi)").performScrollTo().performTextReplacement("3.5")
        rule.onNodeWithText("GPS measured 3.54 mi").assertExists()
    }
    @Test fun `typing the displayed GPS total restores its exact meters`() {
        show()
        val field = rule.onNodeWithText("Distance (mi)")
        field.performScrollTo().performTextReplacement("3.5")
        field.performTextReplacement("3.54")
        rule.runOnIdle { assertEquals("5700", draft.metresText) }
        assertEquals(2, rule.onAllNodesWithText("Measured by GPS").fetchSemanticsNodes().size)
    }
    @Test fun `a reading above the total blocks Save`() {
        show()
        rule.onNodeWithText("Distance at 30:00").performScrollTo().performTextReplacement("3.70")
        rule.onNodeWithText("No more than your total distance").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }
    @Test fun `a valid reading can replace an existing GPS reading`() {
        show()
        rule.onNodeWithText("Distance at 30:00").performScrollTo().performTextReplacement("3.30")
        rule.onNodeWithText("No more than your total distance").assertDoesNotExist()
        rule.onNodeWithText("Save").assertIsEnabled()
    }
}
