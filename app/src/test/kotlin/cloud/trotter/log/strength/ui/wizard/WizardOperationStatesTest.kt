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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.units.DistanceUnit
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

    @Test fun loadingHidesStepInputsAndDisablesNext() {
        var state by mutableStateOf(WizardUiState(initialized = false))
        rule.setContent { AppTheme { WizardScreen(state, actions()) } }
        rule.onNodeWithText("Lifting").assertDoesNotExist()
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.runOnIdle { state = state.copy(initialized = true) }
        rule.onNodeWithText("Lifting").assertExists()
        rule.onNodeWithText("Next").assertIsEnabled()
    }

    @Test fun freshAboutYouIsEmptyAndNextRequiresBothValidValues() {
        var state by mutableStateOf(WizardStateBuilder.buildUiState(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ABOUT_YOU), WizardAnswers(), false))
        rule.setContent { AppTheme { WizardScreen(state, actions().copy(
            onBodyweightChange = { state = state.copy(bodyweightLb = it) },
            onAgeChange = { state = state.copy(age = it) },
        )) } }
        assertEquals("", rule.onNodeWithText("Bodyweight (lb)").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("", rule.onNodeWithText("Age").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.onNodeWithText("Bodyweight (lb)").performTextReplacement("210.5")
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.onNodeWithText("Age").performTextReplacement("33")
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle { assertEquals(210.5, state.bodyweightLb ?: Double.NaN, 0.0) }
        rule.onNodeWithText("Age").performTextReplacement("")
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.onNodeWithText("Age").performTextReplacement("0")
        rule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test fun tappingARadioRowSelectsTheAnswer() {
        var state by mutableStateOf(WizardStateBuilder.buildUiState(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.EMPHASIS), WizardAnswers(), false))
        rule.setContent { AppTheme { WizardScreen(state, actions().copy(onEmphasisChange = {
            state = WizardStateBuilder.buildUiState(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.EMPHASIS), state.answers.copy(config = state.answers.config.copy(emphasis = it)), false)
        })) } }
        rule.onNodeWithText("Strength-leaning").performClick().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.runOnIdle { assertEquals(GoalEmphasis.STRENGTH, state.answers.config.emphasis) }
    }

    @Test fun selectAllChecksEveryEquipmentRowAndClearAllUnchecksThem() {
        var state by mutableStateOf(WizardStateBuilder.buildUiState(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.EQUIPMENT),
            WizardAnswers(equipment = setOf(Equipment.BARBELL)), false))
        rule.setContent { AppTheme { WizardScreen(state, actions().copy(onEquipmentToggle = { equipment ->
            val current = state.answers.equipment
            val changed = if (equipment in current) current - equipment else current + equipment
            state = WizardStateBuilder.buildUiState(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.EQUIPMENT), state.answers.copy(equipment = changed), false)
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
        var state by mutableStateOf(WizardStateBuilder.buildUiState(WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ROTATION), answers, false,
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
            assertEquals(WizardStep.FOCUS, state.step)
            assertEquals(answers, state.answers)
        }
    }

    @Test fun `rotation step shows every lift of day A`() {
        val answers = WizardAnswers()
        val program = ProgramGenerator.generate(answers)
        val state = WizardStateBuilder.buildUiState(
            WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ROTATION), answers, false, previewProgram = program,
        )
        rule.setContent { AppTheme { WizardScreen(state, actions()) } }

        program.days.first().exercises.forEach { pe ->
            rule.onAllNodesWithText(ExerciseLibrary.get(pe.exerciseId).name).onFirst()
                .performScrollTo().assertIsDisplayed()
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
            stepIndex = WizardStateBuilder.steps(WizardAnswers()).indexOf(WizardStep.ROTATION), step = WizardStep.ROTATION,
            previewProgram = cloud.trotter.log.strength.domain.generator.ProgramGenerator.generate(WizardAnswers()),
            finishError = UiText.FileAccessFailure("Disk full"),
        ), actions(onNext = { retries++ })) } }
        rule.onNodeWithText("Couldn't create your plan.").assertIsDisplayed()
        rule.onNodeWithText("Couldn't access that file: Disk full").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun cardioRotationShowsDistancesInTheChosenUnit() {
        val answers = WizardAnswers(cardioFirst = CardioFirst(comfortable = Measure.Distance(4828)))
        val state = WizardStateBuilder.buildUiState(WizardStateBuilder.steps(answers).indexOf(WizardStep.ROTATION),
            answers, false, distanceUnit = DistanceUnit.KM, previewProgram = ProgramGenerator.generate(answers))
        rule.setContent { AppTheme { WizardScreen(state, actions()) } }
        rule.onNodeWithText("Road run · 4.83 km").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Road run · 25:00").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Road run · 7.24 km").performScrollTo().assertIsDisplayed()
    }
}
