package cloud.trotter.log.strength.ui.wizard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class WizardOperationStatesTest {
    @get:Rule val rule = createComposeRule()

    private fun actions(onBack: () -> Unit = {}, onNext: () -> Unit = {}) = WizardActions(
        onNext = onNext, onBack = onBack, onEmphasisChange = {}, onDaysPerWeekChange = {},
        onSplitChange = {}, onAnchorSchemeChange = {}, onDeadliftVariantChange = {},
        onCardioModeChange = {}, onCardioPlacementChange = {}, onFiveKChange = {},
        onBodyweightChange = {}, onUnitChange = {}, onAgeChange = {}, onLevelChange = {},
        onEquipmentToggle = {}, onRestoreFromBackup = {},
    )

    @Test fun tappingARadioRowSelectsTheAnswer() {
        var state by mutableStateOf(WizardStateBuilder.buildUiState(0, WizardAnswers(), false))
        rule.setContent { AppTheme { WizardScreen(state, actions().copy(onEmphasisChange = {
            state = WizardStateBuilder.buildUiState(0, state.answers.copy(config = state.answers.config.copy(emphasis = it)), false)
        })) } }
        rule.onNodeWithText("Strength-leaning").performClick().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.runOnIdle { assertEquals(GoalEmphasis.STRENGTH, state.answers.config.emphasis) }
    }

    @Test fun selectAllChecksEveryEquipmentRowAndClearAllUnchecksThem() {
        var state by mutableStateOf(WizardStateBuilder.buildUiState(WizardStep.EQUIPMENT.ordinal,
            WizardAnswers(equipment = setOf(Equipment.BARBELL)), false))
        rule.setContent { AppTheme { WizardScreen(state, actions().copy(onEquipmentToggle = { equipment ->
            val current = state.answers.equipment
            val changed = if (equipment in current) current - equipment else current + equipment
            state = WizardStateBuilder.buildUiState(WizardStep.EQUIPMENT.ordinal, state.answers.copy(equipment = changed), false)
        })) } }
        rule.onNodeWithText("Select all").performClick()
        rule.runOnIdle { assertEquals(Equipment.entries.toSet(), state.answers.equipment) }
        listOf("Barbell", "Trap Bar", "Dumbbell", "Machine", "Cable", "Bodyweight", "Bench", "Rack", "Pullup Bar", "Ez Bar", "Kettlebell")
            .forEach { rule.onNodeWithText(it).assertIsOn()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)) }
        rule.onNodeWithText("Clear all").performClick()
        rule.onNodeWithText("Barbell").assertIsOff()
        rule.runOnIdle { assertEquals(emptySet<Equipment>(), state.answers.equipment) }
    }

    @Test fun emptyRotationDisablesStartAndReviewKeepsAnswers() {
        val answers = WizardAnswers(daysPerWeek = 3).let { it.copy(config = it.config.copy(emphasis = GoalEmphasis.PHYSIQUE)) }
        var state by mutableStateOf(WizardStateBuilder.buildUiState(WizardStep.ROTATION.ordinal, answers, false,
            previewProgram = Program(days = emptyList())))
        var starts = 0
        rule.setContent { AppTheme { WizardScreen(state, actions(onNext = { starts++ }, onBack = {
            state = WizardStateBuilder.buildUiState(state.stepIndex - 1, state.answers, false)
        })) } }
        rule.onNodeWithText("Start").assertIsNotEnabled().performClick()
        rule.onNodeWithText("These answers don't produce any days. Go back and add days or equipment.").assertIsDisplayed()
        rule.onNodeWithText("Review answers").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(0, starts)
            assertEquals(WizardStep.EMPHASIS, state.step)
            assertEquals(answers, state.answers)
        }
    }

    @Test fun savingShowsAnIndicatorAndNamesTheOperation() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { WizardScreen(WizardUiState(finishInFlight = true), actions()) } }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Creating your plan…").assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test fun restoreUsesTheSameLabelAndIndicatorAsBackup() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { WizardScreen(WizardUiState(restore = WizardRestoreState(offered = true, inFlight = true)), actions()) } }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Restoring backup. Keep this screen open.").assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test fun failedPlanCreationNamesTheFailureAndRetriesThroughTheExistingAction() {
        var retries = 0
        rule.setContent { AppTheme { WizardScreen(WizardUiState(
            stepIndex = WizardStep.ROTATION.ordinal, step = WizardStep.ROTATION,
            previewProgram = cloud.trotter.log.strength.domain.generator.ProgramGenerator.generate(WizardAnswers()),
            finishError = UiText.FileAccessFailure("Disk full"),
        ), actions(onNext = { retries++ })) } }
        rule.onNodeWithText("Couldn't create your plan.").assertIsDisplayed()
        rule.onNodeWithText("Couldn't access that file: Disk full").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }
}
