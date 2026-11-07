package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WizardStateBuilderTest {
    @Test
    fun `lift rows follow the plan's mains and default reps to the top set`() {
        val answers = WizardAnswers()
        val state = WizardStateBuilder.buildUiState(WizardStep.LIFTS.ordinal, answers, false)
        assertEquals(ProgramGenerator.mainLiftIds(answers), state.liftRows.map { it.exerciseId })
        assertEquals(4, state.liftRows.size)
        assertTrue(state.liftRows.all { it.reps == 5 && it.weightLb == null })
        state.liftRows.forEach { row ->
            val entry = ExerciseLibrary.get(row.exerciseId)
            assertEquals(entry.name, row.name)
            assertEquals(entry.perHand, row.perHand)
        }

        val drafted = WizardStateBuilder.buildUiState(
            WizardStep.LIFTS.ordinal, answers, false,
            liftDrafts = listOf(WizardLiftDraft("bb_back_squat", 100.0, null)),
        )
        assertEquals(100.0, drafted.liftRows.first().weightLb)
        assertEquals(5, drafted.liftRows.first().reps)
    }

    @Test
    fun `novice squat preview shows three sets at the empty bar`() {
        val answers = WizardAnswers(config = WizardAnswers().config.copy(level = ExperienceLevel.NOVICE))
        val state = WizardStateBuilder.buildUiState(
            WizardStep.ROTATION.ordinal, answers, false, previewProgram = ProgramGenerator.generate(answers),
        )
        val squat = state.previewLifts.getValue("A").first()
        assertEquals(3, squat.setCount)
        assertEquals("3 × 5 · 45 lb", squat.detail)
    }

    // --- step defaults (spec §6.1 "next-next-next") --------------------------

    @Test
    fun defaultAnswers_match_spec_next_next_next_program() {
        val defaults = WizardAnswers()
        assertEquals(4, defaults.daysPerWeek)
        assertEquals(SplitTemplate.FULL_BODY, defaults.split)
        assertEquals(AnchorScheme.PROTOTYPE, defaults.anchorScheme)
        assertEquals(DeadliftVariant.TRAP_BAR, defaults.deadliftVariant)
        assertEquals(Equipment.entries.toSet(), defaults.equipment)
    }

    @Test
    fun buildUiState_at_step_zero_starts_on_emphasis_with_no_narrowing() {
        val state = WizardStateBuilder.buildUiState(0, WizardAnswers(), isComplete = false)
        assertEquals(WizardStep.EMPHASIS, state.step)
        assertTrue(state.isFirstStep)
        assertFalse(state.isLastStep)
        // 4-day prototype default: all four anchors rotate, nothing narrowed.
        assertEquals(4, state.activeAnchorIds.size)
    }

    @Test
    fun buildUiState_clamps_an_out_of_range_step_index() {
        val tooHigh = WizardStateBuilder.buildUiState(99, WizardAnswers(), isComplete = false)
        assertEquals(WizardStep.entries.last(), tooHigh.step)

        val tooLow = WizardStateBuilder.buildUiState(-3, WizardAnswers(), isComplete = false)
        assertEquals(WizardStep.entries.first(), tooLow.step)
    }

    @Test
    fun buildUiState_last_step_is_rotation() {
        val last = WizardStep.entries.size - 1
        val state = WizardStateBuilder.buildUiState(last, WizardAnswers(), isComplete = false)
        assertEquals(WizardStep.ROTATION, state.step)
        assertTrue(state.isLastStep)
    }

    @Test
    fun `rotation preview lists every exercise of each day`() {
        val answers = WizardAnswers()
        val program = ProgramGenerator.generate(answers)
        val state = WizardStateBuilder.buildUiState(
            WizardStep.ROTATION.ordinal, answers, false, previewProgram = program,
        )

        for (day in program.days) {
            val lifts = state.previewLifts.getValue(day.id)
            val names = day.exercises.map { pe ->
                ExerciseLibrary.get(pe.exerciseId).name +
                    pe.superset?.let { " + " + ExerciseLibrary.get(it.exerciseId).name }.orEmpty()
            }
            assertEquals(names, lifts.map { it.name })
            assertEquals(day.exercises.map { it.targetSets }, lifts.map { it.setCount })
        }
        assertEquals(6, state.previewLifts.getValue("A").size)
        assertEquals("TOP × 5 · 235 lb", state.previewLifts.getValue("A").first().detail)
        assertTrue(state.previewLifts.getValue("B")[4].name.contains(" + "))
    }

    // --- anchor-set narrowing surfaces the :domain SSOT (spec §6.1) -----------
    // The narrowing rule itself is proven in ProgramGeneratorAnchorsTest (the one
    // place it lives); here we only assert the wizard state exposes exactly that
    // helper's output, so preview and generated program can't drift.

    @Test
    fun buildUiState_surfaces_ProgramGenerators_activeAnchorIds_verbatim() {
        val cases = listOf(
            WizardAnswers(daysPerWeek = 2, split = SplitTemplate.FULL_BODY),
            WizardAnswers(daysPerWeek = 3, split = SplitTemplate.FULL_BODY),
            WizardAnswers(daysPerWeek = 4, split = SplitTemplate.FULL_BODY),
            WizardAnswers(daysPerWeek = 5, split = SplitTemplate.FULL_BODY),
            WizardAnswers(daysPerWeek = 4, split = SplitTemplate.UPPER_LOWER),
            WizardAnswers(daysPerWeek = 3, split = SplitTemplate.PPL),
        )
        cases.forEach { answers ->
            val state = WizardStateBuilder.buildUiState(WizardStep.ANCHORS.ordinal, answers, isComplete = false)
            assertEquals(ProgramGenerator.activeAnchorIds(answers), state.activeAnchorIds)
        }
    }

    // --- split validity across a days/week change -----------------------------

    @Test
    fun splitForDays_keeps_a_split_still_offered_at_the_new_day_count() {
        // PPL is 6 days' default and 3 days' alternative — valid at both.
        assertEquals(SplitTemplate.PPL, WizardStateBuilder.splitForDays(SplitTemplate.PPL, 3))
        assertEquals(SplitTemplate.PPL, WizardStateBuilder.splitForDays(SplitTemplate.PPL, 6))
    }

    @Test
    fun splitForDays_falls_back_to_the_new_defaults_split_when_no_longer_offered() {
        // PPLUL only exists at 5 days; dropping to 3 must not leave it selected.
        assertEquals(SplitTemplate.FULL_BODY, WizardStateBuilder.splitForDays(SplitTemplate.PPLUL, 3))
    }

    @Test
    fun splitForDays_is_a_no_op_when_the_current_split_is_already_that_days_default() {
        assertEquals(SplitTemplate.FULL_BODY, WizardStateBuilder.splitForDays(SplitTemplate.FULL_BODY, 4))
    }
}
