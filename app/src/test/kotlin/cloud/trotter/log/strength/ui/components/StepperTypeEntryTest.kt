package cloud.trotter.log.strength.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class StepperTypeEntryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingValueOpensSheetAndDoneUsesStepperRounding() {
        var committed = 135.0
        composeTestRule.setContent {
            var value by remember { mutableDoubleStateOf(135.0) }
            AppTheme {
                Stepper(
                    value = value,
                    onValueChange = {
                        value = it
                        committed = it
                    },
                    step = { WeightStepper.increment(it, WeightUnit.LB) },
                    round = { WeightStepper.round(it, WeightUnit.LB) },
                    format = WeightStepper::format,
                    inputLabel = "weight",
                    inputUnit = "lb",
                    decimalInput = true,
                )
            }
        }

        val typeAction = composeTestRule.onNode(
            SemanticsMatcher("offers Type weight") { node ->
                node.config.getOrNull(SemanticsProperties.CustomActions)?.any { it.label == "Type weight" } == true
            },
        ).fetchSemanticsNode().config[SemanticsProperties.CustomActions].single { it.label == "Type weight" }
        composeTestRule.runOnIdle { typeAction.action() }
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("137")
        composeTestRule.onNodeWithText("DONE").performClick()

        assertEquals(135.0, committed, 0.0)
    }
}
