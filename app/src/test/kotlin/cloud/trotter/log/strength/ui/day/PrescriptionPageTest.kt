package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
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
class PrescriptionPageTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun repsInTimeAllowsAnOptionalAimEachSideAndBandThenSaves() {
        val draft = mutableStateOf(PrescriptionDraft(slotId = 1, seconds = 120, repAim = 40))
        var saved: Prescription? = null
        rule.setContent { AppTheme {
            PrescriptionPage(draft.value, WeightUnit.LB, { draft.value = it }, { saved = draft.value.prescription() }, {})
        } }
        rule.onNodeWithText("Reps in time").performClick()
        rule.onNodeWithContentDescription("Rep aim").performScrollTo().performClick()
        rule.onNodeWithText("40").assertExists()
        rule.onNodeWithContentDescription("Each side").performScrollTo().performClick()
        rule.onNodeWithText("Band or resistance").performScrollTo().performTextReplacement("x".repeat(45))
        assertEquals(40, draft.value.resistance.length)
        rule.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(Prescription(PrescribedTarget.RepsInTime(120, 40), true, "x".repeat(40)), saved)
    }
}
