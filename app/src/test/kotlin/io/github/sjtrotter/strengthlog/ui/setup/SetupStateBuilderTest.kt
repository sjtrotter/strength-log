package io.github.sjtrotter.strengthlog.ui.setup

import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SetupStateBuilder] is the pure logic behind the setup screen's live GOAL
 * preview and unit-aware bodyweight display. These tests prove it *delegates*
 * rather than reimplements: every GOAL preview number must equal
 * [GoalCalculator.goalFor] computed independently, and every display
 * conversion must equal [WeightUnit]'s own conversion — never a second lb/kg
 * formula living in the UI layer.
 */
class SetupStateBuilderTest {

    // --- goalPreview delegates to GoalCalculator (SSOT) -----------------------

    @Test
    fun goalPreview_matches_spec_pinned_defaults() {
        // spec §11: LifterConfig(bodyweightLb=235, age=40, INTERMEDIATE, BALANCED)
        // -> Squat 235, Bench 195, Trap-bar DL 255, Incline DB 75/hand.
        val cfg = LifterConfig(bodyweightLb = 235, age = 40, level = ExperienceLevel.INTERMEDIATE, emphasis = GoalEmphasis.BALANCED)
        val preview = SetupStateBuilder.goalPreview(cfg, WizardAnswers(), WeightUnit.LB)

        assertEquals(4, preview.size)
        assertEquals("235", preview[0].display) // squat
        assertEquals("195", preview[1].display) // bench
        assertEquals("255", preview[2].display) // trap-bar deadlift
        assertEquals("75", preview[3].display) // incline DB
        assertEquals(true, preview[3].perHand)
    }

    @Test
    fun goalPreview_never_reimplements_goal_math_for_arbitrary_configs() {
        val configs = listOf(
            LifterConfig(bodyweightLb = 180, age = 25, level = ExperienceLevel.NOVICE, emphasis = GoalEmphasis.STRENGTH),
            LifterConfig(bodyweightLb = 210, age = 55, level = ExperienceLevel.ADVANCED, emphasis = GoalEmphasis.PHYSIQUE),
            LifterConfig(bodyweightLb = 300, age = 40, level = ExperienceLevel.INTERMEDIATE, emphasis = GoalEmphasis.BALANCED),
        )
        val answersVariants = listOf(
            WizardAnswers(anchorScheme = AnchorScheme.PROTOTYPE, deadliftVariant = DeadliftVariant.TRAP_BAR),
            WizardAnswers(anchorScheme = AnchorScheme.BIG_4, deadliftVariant = DeadliftVariant.CONVENTIONAL),
            WizardAnswers(anchorScheme = AnchorScheme.FIVE_THREE_ONE, deadliftVariant = DeadliftVariant.SUMO),
        )
        configs.forEach { cfg ->
            answersVariants.forEach { answers ->
                val preview = SetupStateBuilder.goalPreview(cfg, answers, WeightUnit.LB)
                val expectedIds = ProgramGenerator.anchorIds(answers)
                assertEquals(expectedIds.size, preview.size)
                expectedIds.forEachIndexed { i, id ->
                    val entry = ExerciseLibrary.get(id)
                    val expectedGoalLb = GoalCalculator.goalFor(entry, cfg)
                    assertEquals(WeightStepper.format(expectedGoalLb), preview[i].display)
                    assertEquals(entry.name, preview[i].name)
                    assertEquals(entry.perHand, preview[i].perHand)
                }
            }
        }
    }

    @Test
    fun goalPreview_changes_with_every_config_input_live() {
        val base = LifterConfig(bodyweightLb = 200, age = 30, level = ExperienceLevel.INTERMEDIATE, emphasis = GoalEmphasis.BALANCED)
        val basePreview = SetupStateBuilder.goalPreview(base, WizardAnswers(), WeightUnit.LB)

        val heavier = SetupStateBuilder.goalPreview(base.copy(bodyweightLb = 260), WizardAnswers(), WeightUnit.LB)
        val older = SetupStateBuilder.goalPreview(base.copy(age = 60), WizardAnswers(), WeightUnit.LB)
        val advanced = SetupStateBuilder.goalPreview(base.copy(level = ExperienceLevel.ADVANCED), WizardAnswers(), WeightUnit.LB)
        val physique = SetupStateBuilder.goalPreview(base.copy(emphasis = GoalEmphasis.PHYSIQUE), WizardAnswers(), WeightUnit.LB)

        assertEquals(false, basePreview == heavier)
        assertEquals(false, basePreview == older)
        assertEquals(false, basePreview == advanced)
        assertEquals(false, basePreview == physique)
    }

    // --- unit-toggle display conversion delegates to :domain WeightUnit ------

    @Test
    fun bodyweightDisplay_delegates_to_WeightUnit_conversion() {
        val cfg = LifterConfig(bodyweightLb = 220)
        assertEquals(220.0, SetupStateBuilder.bodyweightDisplay(cfg, WeightUnit.LB))
        assertEquals(WeightUnit.KG.fromLb(220.0), SetupStateBuilder.bodyweightDisplay(cfg, WeightUnit.KG))
    }

    @Test
    fun bodyweightLb_round_trips_through_WeightUnit_conversion() {
        assertEquals(220, SetupStateBuilder.bodyweightLb(220.0, WeightUnit.LB))
        val kgDisplay = WeightUnit.KG.fromLb(220.0)
        assertEquals(220, SetupStateBuilder.bodyweightLb(kgDisplay, WeightUnit.KG))
    }

    @Test
    fun goalPreview_display_switches_with_unit_via_WeightUnit_and_WeightStepper() {
        val cfg = LifterConfig(bodyweightLb = 235, age = 40, level = ExperienceLevel.INTERMEDIATE, emphasis = GoalEmphasis.BALANCED)
        val lbPreview = SetupStateBuilder.goalPreview(cfg, WizardAnswers(), WeightUnit.LB)
        val kgPreview = SetupStateBuilder.goalPreview(cfg, WizardAnswers(), WeightUnit.KG)

        val squatGoalLb = GoalCalculator.goalFor(ExerciseLibrary.get("bb_back_squat"), cfg)
        assertEquals(WeightStepper.format(squatGoalLb), lbPreview[0].display)
        assertEquals(WeightStepper.format(WeightUnit.KG.fromLb(squatGoalLb)), kgPreview[0].display)
    }

    // --- buildUiState wires everything together -------------------------------

    @Test
    fun buildUiState_assembles_config_cardio_unit_and_preview() {
        val cfg = LifterConfig(bodyweightLb = 235, age = 40)
        val cardio = CardioPrefs()
        val state = SetupStateBuilder.buildUiState(cfg, cardio, WeightUnit.LB, WizardAnswers())

        assertEquals(cfg, state.config)
        assertEquals(cardio, state.cardio)
        assertEquals(WeightUnit.LB, state.unit)
        assertEquals(235.0, state.bodyweightDisplay)
        assertEquals(4, state.goalPreview.size)
    }
}
