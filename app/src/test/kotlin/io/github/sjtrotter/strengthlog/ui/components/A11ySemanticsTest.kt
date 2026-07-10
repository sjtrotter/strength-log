package io.github.sjtrotter.strengthlog.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
// DayTab is both a data class (DayScreenModels) and the day-tab composable
// (DayScreen, made `internal` for this test) living in the same package —
// the wildcard import resolves each call by shape, exactly as it already
// does inside DayScreen.kt itself.
import io.github.sjtrotter.strengthlog.ui.day.*
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.accentSoft
import io.github.sjtrotter.strengthlog.ui.theme.dayAccent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Semantics smoke tests (A7, issue #21): a lean Robolectric + compose-ui-test
 * pass pinning the TalkBack-facing contract the day-screen components promise
 * — not a full accessibility audit (that's the on-device TalkBack checklist
 * in the #21 PR description), just enough to catch a regression that strips
 * a content description or state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11ySemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun stepperSegmentsExposeTheirDecreaseAndIncreaseLabels() {
        composeTestRule.setContent {
            AppTheme {
                Stepper(
                    value = 135.0,
                    onValueChange = {},
                    step = { WeightStepper.increment(it, WeightUnit.LB) },
                    format = WeightStepper::format,
                    round = { WeightStepper.round(it, WeightUnit.LB) },
                    decreaseDescription = "Decrease weight",
                    increaseDescription = "Increase weight",
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Decrease weight").assertExists()
        composeTestRule.onNodeWithContentDescription("Increase weight").assertExists()
    }

    @Test
    fun checkmarkToggleExposesToggleableState() {
        composeTestRule.setContent {
            AppTheme {
                var checked by remember { mutableStateOf(false) }
                CheckmarkToggle(checked = checked, onCheckedChange = { checked = it })
            }
        }

        val toggle = composeTestRule.onNodeWithContentDescription("Set done")
        toggle.assertIsOff()
        toggle.performClick()
        toggle.assertIsOn()
    }

    @Test
    fun setRowExposesWeightAndRepsDescriptions() {
        composeTestRule.setContent {
            AppTheme {
                SetRow(
                    kindLabel = "R1",
                    accent = dayAccent(0),
                    accentSoft = accentSoft(0),
                    weight = 135.0,
                    onWeightChange = {},
                    weightStep = { WeightStepper.increment(it, WeightUnit.LB) },
                    weightFormat = WeightStepper::format,
                    weightRound = { WeightStepper.round(it, WeightUnit.LB) },
                    reps = 5,
                    onRepsChange = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Decrease weight").assertExists()
        composeTestRule.onNodeWithContentDescription("Increase weight").assertExists()
        composeTestRule.onNodeWithContentDescription("Decrease reps").assertExists()
        composeTestRule.onNodeWithContentDescription("Increase reps").assertExists()
        composeTestRule.onNodeWithContentDescription("Remove set").assertExists()
    }

    @Test
    fun dayTabExposesSelectedState() {
        composeTestRule.setContent {
            AppTheme {
                DayTab(
                    tab = DayTab(dayId = "A", dayIndex = 0, isSuggested = false, isSelected = true),
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Day A").assertIsSelected()
    }
}
