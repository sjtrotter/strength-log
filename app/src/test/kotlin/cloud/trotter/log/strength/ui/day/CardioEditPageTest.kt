package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CardioEditPageTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun distanceEndWithTimeAimAndHardSavesTheDisplayedSpec() {
        val draft = mutableStateOf(CardioSetDraft())
        var saved: CardioSetSpec? = null
        rule.setContent { AppTheme {
            CardioSetPage(draft.value, { draft.value = it }, { saved = draft.value.spec() }, {})
        } }
        rule.onNodeWithText("Distance").performClick()
        rule.onNodeWithText("400").assertExists()
        rule.onNodeWithContentDescription("Aim").performScrollTo().performClick()
        val typeAction = rule.onNode(SemanticsMatcher("offers Type Seconds") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Type Seconds" } == true
        }).performScrollTo().fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Type Seconds" }
        rule.runOnIdle { typeAction.action() }
        rule.onNode(hasSetTextAction()).performTextReplacement("105")
        rule.onNodeWithText("DONE").performClick()
        rule.onNodeWithContentDescription("Hard").performScrollTo().performClick()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true), saved)
    }

    @Test
    fun activityDropdownAndLegacyTemplatesUpdateTheBlockDraft() {
        val draft = mutableStateOf(CardioBlockDraft.new(1, "A"))
        rule.setContent { AppTheme {
            CardioBlockPage(draft.value, { draft.value = it }, {}, {})
        } }
        rule.onNodeWithText("Road run").performClick()
        rule.onNodeWithText("Trail run").performClick()
        rule.onNodeWithText("Tempo 20:00").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(CardioActivity.TRAIL_RUN, draft.value.activity)
            assertEquals(Measure.Time(1200), draft.value.block().sets.single().end)
        }
        rule.onNodeWithText("Intervals 5 × 2:00").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(11, draft.value.sets.size) }
    }
}
