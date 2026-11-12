package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CardioDistanceSheetGpsTest {
    @get:Rule val rule = createComposeRule()
    private fun show(complete: Boolean) {
        val base = requireNotNull(runningCardioFixture().run).copy(
            results = listOf(SavedCardioResult("FINISHED", 1900, 5697)), wallStart = null,
            drafts = listOf(CardioDistanceDraft(0, false, "5697")))
        var run by mutableStateOf(base)
        var input by mutableStateOf<CardioDistanceInput?>(null)
        rule.setContent { AppTheme {
            CardioDistanceSheet(run, 0, workoutActions().copy(onCardioDistanceChange = { text ->
                input = CardioDistanceInput(run.runId, 0, text, DistanceUnit.MI)
                run = run.copy(drafts = listOf(run.drafts.single().copy(text = DistanceUnit.MI.toMetres(text.toDouble())?.toString().orEmpty())))
            }), DistanceUnit.MI, input, gpsSets = listOf(CardioGpsSet(run.runId, 0, 5697, null, null, complete, false)))
        } }
    }
    @Test fun `a measured draft labels its GPS starting point`() {
        show(true)
        rule.onNodeWithText("Measured by GPS").assertExists()
    }
    @Test fun `editing the draft shows the original GPS value`() {
        show(true)
        rule.onNodeWithText("Distance (mi)").performTextReplacement("3.11")
        rule.onNodeWithText("GPS measured 3.54 mi").assertExists()
    }
    @Test fun `a partial draft always shows lost signal`() {
        show(false)
        rule.onNodeWithText("GPS lost signal").assertExists()
    }
}
