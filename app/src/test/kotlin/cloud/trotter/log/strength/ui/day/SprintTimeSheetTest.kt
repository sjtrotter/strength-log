package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SprintTimeSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `a typed time saves its method and protocol`() {
        val draft = mutableStateOf(SprintTimeDraft("run", 1, "", "HAND", "THREE_POINT"))
        var saved: Triple<Int, TimingMethod, StartProtocol>? = null
        rule.setContent { AppTheme {
            SprintTimeSheet(draft.value, { draft.value = it }, { centis, method, protocol ->
                saved = Triple(centis, method, protocol)
            }, {})
        } }
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Time (s)").performTextReplacement("8.3")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Electronic gates").performScrollTo().performClick()
        rule.onNodeWithText("Save").performClick()
        assertEquals(Triple(830, TimingMethod.GATES, StartProtocol.THREE_POINT), saved)
    }

    @Test @Config(fontScale = 2f)
    fun largeFontSprintTimeSheet() {
        val draft = mutableStateOf(SprintTimeDraft("run", 1, "8.30", "HAND", "STANDING"))
        rule.setContent { AppTheme { SprintTimeSheet(draft.value, { draft.value = it }, { _, _, _ -> }, {}) } }
        rule.onNodeWithText("Rep time").assertIsDisplayed()
        rule.onNodeWithText("Time (s)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        rule.onNodeWithText("Flying").performScrollTo().assertIsDisplayed()
    }
}
