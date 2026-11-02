package cloud.trotter.log.strength.ui.you

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun lifterFactsShowDefaultsAndForwardLevelAndBodyweightChanges() {
        var level: ExperienceLevel? = null
        val bodyweights = mutableListOf<Double>()
        rule.setContent { AppTheme {
            YouScreen(
                YouUiState(config = LifterConfig(), unit = WeightUnit.LB),
                YouActions(
                    onBodyweightChange = { bodyweights += it }, onAgeChange = {},
                    onLevelChange = { level = it }, onBack = {},
                ),
            )
        } }
        rule.onNodeWithText("235").assertExists()
        rule.onNodeWithText("Intermediate").assertIsSelected()
        rule.onNodeWithText("Generated plans set their goals from these.").assertExists()
        rule.onNodeWithText("Advanced").performClick()
        assertEquals(ExperienceLevel.ADVANCED, level)
        rule.onNodeWithContentDescription("Increase bodyweight").performClick()
        assertEquals(1, bodyweights.size)
        assertTrue(bodyweights.single() > 235.0)
    }
}
