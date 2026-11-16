package cloud.trotter.log.strength.ui.you

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.data.LifterProfile
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
class YouScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun metricBodyweightKeepsTenthsWithoutPlateRounding() {
        val bodyweights = mutableListOf<Double>()
        rule.setContent { AppTheme {
            YouScreen(
                YouUiState(unit = WeightUnit.KG),
                YouActions(onBodyweightChange = { bodyweights += it }, onAgeChange = {}, onBack = {}),
            )
        } }
        rule.onNodeWithText("Bodyweight (kg)").performClick().performTextReplacement("95.5")
        rule.onNodeWithText("Bodyweight (kg)").performImeAction()
        assertEquals(listOf(95.5), bodyweights)
    }

    @Test
    fun lifterFactsShowProfileAndForwardBodyweightChanges() {
        val bodyweights = mutableListOf<Double>()
        rule.setContent { AppTheme {
            YouScreen(
                YouUiState(profile = LifterProfile(235.0, 40), unit = WeightUnit.LB),
                YouActions(
                    onBodyweightChange = { bodyweights += it }, onAgeChange = {},
                    onBack = {},
                ),
            )
        } }
        rule.onNodeWithText("235").assertExists()
        rule.onNodeWithText("Generated plans set their goals from these.").assertDoesNotExist()
        rule.onNodeWithText("Bodyweight (lb)").performClick().performTextReplacement("250")
        rule.onNodeWithText("Bodyweight (lb)").performImeAction()
        assertEquals(listOf(250.0), bodyweights)
        rule.onNodeWithText("Bodyweight (lb)").performClick().performTextReplacement("")
        rule.onNodeWithText("Bodyweight (lb)").performImeAction()
        assertEquals(listOf(250.0), bodyweights)
        rule.onNodeWithText("Enter a weight of at least 1").assertExists()
    }
    @Test
    fun `an empty profile shows empty fields and no level`() {
        rule.setContent { AppTheme { YouScreen(YouUiState(), YouActions({}, {}, {})) } }
        rule.onNodeWithText("Bodyweight (lb)").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        rule.onNodeWithText("Age").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        rule.onNodeWithText("Experience level").assertDoesNotExist()
    }
}
