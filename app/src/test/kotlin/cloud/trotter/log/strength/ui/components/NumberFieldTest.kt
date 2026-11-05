package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class NumberFieldTest {
    @get:Rule val rule = createComposeRule()

    private fun fields(onCommit: (Double) -> Unit) {
        rule.setContent { AppTheme { Column {
            NumberField(135.0, onCommit, "Weight (lb)", minValue = 1.0, maxValue = 500.0,
                format = WeightStepper::format, round = { WeightStepper.round(it, WeightUnit.LB) },
                decimalInput = true, weightInput = true)
            NumberField(8.0, {}, "Reps")
        } } }
    }

    @Test fun doneRoundsAndCommitsOnceEvenWhenItAlsoClearsFocus() {
        val writes = mutableListOf<Double>()
        fields { writes += it }
        rule.onNodeWithText("Weight (lb)").performClick().performTextReplacement("142")
        rule.onNodeWithText("Weight (lb)").performImeAction()
        assertEquals(listOf(140.0), writes)
    }

    @Test fun losingFocusCommitsTheVisibleDraft() {
        val writes = mutableListOf<Double>()
        fields { writes += it }
        rule.onNodeWithText("Weight (lb)").performClick().performTextReplacement("150")
        rule.onNodeWithText("Reps").performClick()
        rule.runOnIdle { assertEquals(listOf(150.0), writes) }
    }

    @Test fun blankAndOutOfRangeStayVisibleWithAnErrorAndNeverWrite() {
        val writes = mutableListOf<Double>()
        fields { writes += it }
        rule.onNodeWithText("Weight (lb)").performClick().performTextReplacement("")
        rule.onNodeWithText("Weight (lb)").performImeAction()
        rule.onNodeWithText("Enter a weight between 1 and 500").assertExists()
        rule.onNodeWithText("Weight (lb)").performClick().performTextReplacement("501")
        rule.onNodeWithText("Reps").performClick()
        rule.runOnIdle { assertEquals(emptyList<Double>(), writes) }
    }

    @Test fun delayedCommitAcknowledgmentPreservesTheNextDraft() {
        val persisted = mutableStateOf(135.0)
        val writes = mutableListOf<Double>()
        rule.setContent { AppTheme {
            NumberField(persisted.value, { writes += it }, "Bodyweight (lb)")
        } }
        rule.onNodeWithText("Bodyweight (lb)").performClick().performTextReplacement("250")
        rule.onNodeWithText("Bodyweight (lb)").performImeAction()
        rule.onNodeWithText("Bodyweight (lb)").performClick().performTextReplacement("260")

        rule.runOnIdle { persisted.value = 250.0 }

        rule.onNodeWithText("260").assertExists()
        rule.onNodeWithText("Bodyweight (lb)").performImeAction()
        rule.runOnIdle { assertEquals(listOf(250.0, 260.0), writes) }
    }

    @Test fun externalValuePreservesAnInvalidDraftEvenAfterFocusLoss() {
        val persisted = mutableStateOf(135.0)
        val writes = mutableListOf<Double>()
        rule.setContent { AppTheme { Column {
            NumberField(persisted.value, { writes += it }, "Weight (lb)")
            NumberField(8.0, {}, "Reps")
        } } }
        rule.onNodeWithText("Weight (lb)").performClick().performTextReplacement("invalid")
        rule.onNodeWithText("Reps").performClick()

        rule.runOnIdle { persisted.value = 250.0 }

        rule.onNodeWithText("invalid").assertExists()
        rule.runOnIdle { assertEquals(emptyList<Double>(), writes) }
    }

    @Test fun externalValuePreservesAFocusedFieldWithoutEdits() {
        val persisted = mutableStateOf(135.0)
        rule.setContent { AppTheme { NumberField(persisted.value, {}, "Weight (lb)") } }
        rule.onNodeWithText("Weight (lb)").performClick()

        rule.runOnIdle { persisted.value = 250.0 }

        rule.onNodeWithText("135").assertExists()
    }

    @Test fun externalValueAppearsOnceAFocusedFieldWithoutEditsLosesFocus() {
        val persisted = mutableStateOf(135.0)
        lateinit var focusManager: FocusManager
        rule.setContent { AppTheme {
            focusManager = LocalFocusManager.current
            NumberField(persisted.value, {}, "Weight (lb)")
        } }
        rule.onNodeWithText("Weight (lb)").performClick()
        rule.runOnIdle { persisted.value = 250.0 }
        rule.onNodeWithText("135").assertExists()

        rule.runOnIdle { focusManager.clearFocus() }

        rule.onNodeWithText("250").assertExists()
    }

    @Test fun externalValueUpdatesAnIdleField() {
        val persisted = mutableStateOf(135.0)
        rule.setContent { AppTheme { NumberField(persisted.value, {}, "Weight (lb)") } }

        rule.runOnIdle { persisted.value = 250.0 }

        rule.onNodeWithText("250").assertExists()
    }

    @Test fun invalidNumbersNeverReachRounding() {
        listOf("", " ", "NaN", "Infinity", "-1", "501").forEach { draft ->
            assertNull(resolveNumberCommit(draft, 1.0, 500.0) { error("Invalid value reached rounding") })
        }
    }
}
