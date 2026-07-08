package io.github.sjtrotter.strengthlog.domain

import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [ProgramGenerator.activeAnchorIds] — the single source of the spec
 * §6.1 "first N anchors for 2–3-day splits" narrowing that both the generated
 * full-body program and the wizard's anchor-step preview read. Kept in a new
 * file so the pinned §11 verification tests stay untouched.
 */
class ProgramGeneratorAnchorsTest {

    @Test
    fun fullBody_narrowsToTheFirstTwoAnchorsAtTwoDays() {
        val answers = WizardAnswers(daysPerWeek = 2, split = SplitTemplate.FULL_BODY)
        assertEquals(ProgramGenerator.anchorIds(answers).take(2), ProgramGenerator.activeAnchorIds(answers))
    }

    @Test
    fun fullBody_narrowsToTheFirstThreeAnchorsAtThreeDays() {
        val answers = WizardAnswers(daysPerWeek = 3, split = SplitTemplate.FULL_BODY)
        assertEquals(ProgramGenerator.anchorIds(answers).take(3), ProgramGenerator.activeAnchorIds(answers))
    }

    @Test
    fun fullBody_usesAllFourAnchorsAtFourDays() {
        val answers = WizardAnswers(daysPerWeek = 4, split = SplitTemplate.FULL_BODY)
        assertEquals(ProgramGenerator.anchorIds(answers), ProgramGenerator.activeAnchorIds(answers))
    }

    @Test
    fun fullBody_usesAllAnchorsAtFiveDays_whereTheGeneratorCyclesRatherThanNarrows() {
        val answers = WizardAnswers(daysPerWeek = 5, split = SplitTemplate.FULL_BODY)
        assertEquals(ProgramGenerator.anchorIds(answers), ProgramGenerator.activeAnchorIds(answers))
    }

    @Test
    fun nonFullBodySplits_areNeverNarrowed_evenAtLowDayCounts() {
        val upperLower = WizardAnswers(daysPerWeek = 4, split = SplitTemplate.UPPER_LOWER)
        assertEquals(ProgramGenerator.anchorIds(upperLower), ProgramGenerator.activeAnchorIds(upperLower))

        val ppl = WizardAnswers(daysPerWeek = 3, split = SplitTemplate.PPL)
        assertEquals(ProgramGenerator.anchorIds(ppl), ProgramGenerator.activeAnchorIds(ppl))
    }

    @Test
    fun activeAnchorIds_matchesTheAnchorMainsInTheGeneratedFullBodyProgram() {
        // The preview promise: activeAnchorIds are exactly the anchor mains the
        // generator actually places, so preview and program can't drift.
        val answers = WizardAnswers(daysPerWeek = 3, split = SplitTemplate.FULL_BODY)
        val program = ProgramGenerator.generate(answers).program
        val mainIdsInOrder = program.days.map { day -> day.exercises.first { it.isMain }.exerciseId }
        assertEquals(ProgramGenerator.activeAnchorIds(answers), mainIdsInOrder)
        // Sanity: every active anchor resolves to a real catalog entry.
        ProgramGenerator.activeAnchorIds(answers).forEach { ExerciseLibrary.get(it) }
    }
}
