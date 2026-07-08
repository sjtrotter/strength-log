package io.github.sjtrotter.strengthlog.ui.wizard

import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WizardStateBuilderTest {

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
    fun buildUiState_last_step_is_equipment() {
        val last = WizardStep.entries.size - 1
        val state = WizardStateBuilder.buildUiState(last, WizardAnswers(), isComplete = false)
        assertEquals(WizardStep.EQUIPMENT, state.step)
        assertTrue(state.isLastStep)
    }

    // --- anchor-set narrowing (spec §6.1: 2-3 day splits use only the first N) --

    @Test
    fun activeAnchorIds_narrows_to_two_for_a_two_day_full_body_split() {
        val answers = WizardAnswers(daysPerWeek = 2, split = SplitTemplate.FULL_BODY)
        val expected = ProgramGenerator.anchorIds(answers).take(2)
        assertEquals(expected, WizardStateBuilder.activeAnchorIds(answers))
    }

    @Test
    fun activeAnchorIds_narrows_to_three_for_a_three_day_full_body_split() {
        val answers = WizardAnswers(daysPerWeek = 3, split = SplitTemplate.FULL_BODY)
        val expected = ProgramGenerator.anchorIds(answers).take(3)
        assertEquals(expected, WizardStateBuilder.activeAnchorIds(answers))
    }

    @Test
    fun activeAnchorIds_is_unnarrowed_at_four_days_full_body() {
        val answers = WizardAnswers(daysPerWeek = 4, split = SplitTemplate.FULL_BODY)
        assertEquals(ProgramGenerator.anchorIds(answers), WizardStateBuilder.activeAnchorIds(answers))
    }

    @Test
    fun activeAnchorIds_is_unnarrowed_for_non_full_body_splits_even_at_low_days() {
        // Upper/Lower and PPL assign anchors by pattern match, not by count —
        // the spec's narrowing rule only ever applied to the full-body rotation.
        val upperLower = WizardAnswers(daysPerWeek = 4, split = SplitTemplate.UPPER_LOWER)
        assertEquals(ProgramGenerator.anchorIds(upperLower), WizardStateBuilder.activeAnchorIds(upperLower))

        val ppl = WizardAnswers(daysPerWeek = 3, split = SplitTemplate.PPL)
        assertEquals(ProgramGenerator.anchorIds(ppl), WizardStateBuilder.activeAnchorIds(ppl))
    }

    @Test
    fun activeAnchorIds_is_unnarrowed_for_a_five_day_full_body_split() {
        // 5 > the 4 available anchors, so the generator cycles them instead of
        // narrowing (ProgramGenerator.fullBodyDay's "else anchors" branch).
        val answers = WizardAnswers(daysPerWeek = 5, split = SplitTemplate.FULL_BODY)
        assertEquals(ProgramGenerator.anchorIds(answers), WizardStateBuilder.activeAnchorIds(answers))
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
