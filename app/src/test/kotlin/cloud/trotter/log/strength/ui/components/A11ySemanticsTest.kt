package cloud.trotter.log.strength.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.AllSetsSheet
import cloud.trotter.log.strength.ui.day.DayEditUiState
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.EquipmentFilterRow
import cloud.trotter.log.strength.ui.day.PartnerRowState
import cloud.trotter.log.strength.ui.day.SetEditSheet
import cloud.trotter.log.strength.ui.day.WeightSwapAffordance
import cloud.trotter.log.strength.ui.day.WeightSwapConfirmDialog
import cloud.trotter.log.strength.ui.day.pagerActions
import cloud.trotter.log.strength.ui.day.pagerEditActions
import cloud.trotter.log.strength.ui.day.pagerFixture
import cloud.trotter.log.strength.ui.licenses.LicenseEntry
import cloud.trotter.log.strength.ui.licenses.LicensesScreen
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.dayAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Semantics smoke tests (A7): a lean Robolectric + compose-ui-test pass
 * pinning the TalkBack-facing contract promised by key app components, enough
 * to catch a regression that strips a content description or state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11ySemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dayIndexExposesTheDayDescription() {
        composeTestRule.setContent {
            AppTheme { DayIndex(dayIndex = 3, letter = "D", size = DayIndexSize.Large) }
        }

        composeTestRule.onNodeWithContentDescription("Day D").assertExists()
    }

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
    @Config(qualifiers = "w411dp-h891dp")
    fun allSetsCheckboxNamesTheSetAndItsValue() {
        val card = pagerFixture().exercises[1]
        val row = card.rows[1].copy(kind = SetKind.RAMP, rampIndex = 3)
        composeTestRule.setContent {
            AppTheme { AllSetsSheet(card.copy(rows = listOf(row)), pagerActions(), onEdit = {}, onDismiss = {}) }
        }
        composeTestRule.onNodeWithContentDescription("Ramp set 3, 95 × 5")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsOff()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun editSteppersNameTheirFieldAndPartnerTrack() {
        val card = pagerFixture().exercises[1].copy(
            isSuperset = true,
            partnerTitle = "Pull-up",
            partnerTracking = TrackingType.REPS,
        )
        val row = card.rows[1].copy(partner = PartnerRowState(0.0, 6))
        composeTestRule.setContent {
            AppTheme { SetEditSheet(card, row, WeightUnit.LB, 3, pagerActions(), onDismiss = {}) }
        }
        listOf(
            "Weight, 5 less", "Weight, 5 more", "Reps, one less", "Reps, one more",
            "Pull-up, Reps, one less", "Pull-up, Reps, one more",
        ).forEach { composeTestRule.onNodeWithContentDescription(it).assertExists() }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun timedEditSteppersNameSecondsOnBothTracks() {
        val card = pagerFixture().exercises[1].copy(
            tracking = TrackingType.TIMED,
            isSuperset = true,
            partnerTitle = "Plank",
            partnerTracking = TrackingType.TIMED,
        )
        val row = card.rows[1].copy(seconds = 45, partner = PartnerRowState(0.0, 0, seconds = 30))
        composeTestRule.setContent {
            AppTheme { SetEditSheet(card, row, WeightUnit.LB, 3, pagerActions(), onDismiss = {}) }
        }
        listOf("Seconds, 5 less", "Seconds, 5 more", "Plank, Seconds, 5 less", "Plank, Seconds, 5 more")
            .forEach { composeTestRule.onNodeWithContentDescription(it).assertExists() }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun focusNumeralIsAButtonWithAnEditAction() {
        composeTestRule.setContent {
            AppTheme { DayScreen(pagerFixture(), pagerActions(), DayEditUiState(), pagerEditActions()) }
        }
        val numeral = composeTestRule.onNodeWithText("95 × 5")
        numeral.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals("Edit", numeral.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)
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
    fun selectionCardExposesSelectedRadioButtonContract() {
        composeTestRule.setContent {
            AppTheme {
                SelectionCard(title = "Balanced", selected = true, onClick = {})
            }
        }

        composeTestRule.onNodeWithText("Balanced")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    @Test
    fun multiChoiceSelectionCardIsACheckboxNotARadioButton() {
        // Equipment lists toggle several of many; announcing exclusivity there
        // would promise behavior the control doesn't have.
        composeTestRule.setContent {
            AppTheme {
                SelectionCard(title = "Barbell", selected = true, onClick = {}, mode = SelectionMode.Check)
            }
        }

        composeTestRule.onNodeWithText("Barbell")
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    }

    @Test
    fun selectedEquipmentFilterPillIsACheckboxAndTogglesOnce() {
        var toggleCount = 0
        val equipment = Equipment.entries.first()
        composeTestRule.setContent {
            AppTheme {
                EquipmentFilterRow(
                    options = listOf(equipment),
                    selected = setOf(equipment),
                    accent = dayAccent(0),
                    onToggle = { toggleCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(equipment.name, substring = true, ignoreCase = true)
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
        assertEquals(1, toggleCount)
    }

    @Test
    fun weightSwapConfirmDialogNamesTheTargetAndConfirmingInvokesTheCallback() {
        var confirmed = false
        composeTestRule.setContent {
            AppTheme {
                WeightSwapConfirmDialog(
                    swap = WeightSwapAffordance("weighted_pullup", "Weighted Pull-Up", isRemove = false),
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Switch to Weighted Pull-Up?").assertExists()
        composeTestRule.onNodeWithText("Switch").performClick()
        assertTrue(confirmed)
    }

    // --- License navigation ---------------------------------------------------

    @Test
    fun licensesBackButtonExposesOnlyTheVectorDescription() {
        composeTestRule.setContent {
            AppTheme {
                LicensesScreen(
                    entries = listOf(LicenseEntry("Barlow Condensed (SIL OFL 1.1)", "…")),
                    onBack = {},
                )
            }
        }

        val back = composeTestRule.onNodeWithContentDescription("Back")
        back.assertExists()
        val config = back.fetchSemanticsNode().config
        assertEquals(listOf("Back"), config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
    }

    @Test
    fun closeActionExposesOnlyTheRequestedVectorDescription() {
        composeTestRule.setContent { AppTheme { CloseAction(onClick = {}, contentDescription = "Cancel") } }

        val close = composeTestRule.onNodeWithContentDescription("Cancel")
        close.assertExists()
        val config = close.fetchSemanticsNode().config
        assertEquals(listOf("Cancel"), config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
    }
}
