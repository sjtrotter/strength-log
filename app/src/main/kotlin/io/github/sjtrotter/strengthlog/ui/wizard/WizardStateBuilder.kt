package io.github.sjtrotter.strengthlog.ui.wizard

import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.SplitDefaults
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers

/**
 * The pure decision logic behind the wizard: which split stays valid when the
 * day count changes, which anchors will actually rotate this cycle, and the
 * per-step [WizardUiState] snapshot. Kept free of Android/Hilt so it is
 * unit-testable on the JVM; [WizardViewModel] only wires this to
 * [androidx.lifecycle.SavedStateHandle] and repository writes — the same split
 * [io.github.sjtrotter.strengthlog.ui.day.DayScreenBuilder] draws for the day
 * screen. No generator math is reimplemented here: anchor ids come straight
 * from [ProgramGenerator.anchorIds] (SSOT).
 */
object WizardStateBuilder {

    /**
     * The anchors that will actually appear in rotation for [answers]. Spec
     * §6.1: "For 2–3-day splits only the first N anchors are used in
     * rotation" — this only bites full-body splits at 2 or 3 days/week, since
     * [ProgramGenerator] only narrows when `daysPerWeek <= anchors.size` (4);
     * every other split assigns anchors by pattern match, not by count, so
     * nothing is narrowed for them.
     */
    fun activeAnchorIds(answers: WizardAnswers): List<String> {
        val all = ProgramGenerator.anchorIds(answers)
        return if (answers.split == SplitTemplate.FULL_BODY && answers.daysPerWeek <= all.size) {
            all.take(answers.daysPerWeek)
        } else {
            all
        }
    }

    /**
     * Keeps [current] if the new [days] count still offers it (as default or
     * alternative, spec §6.2); otherwise falls back to that count's default so
     * the wizard is never left pointed at a split the day count no longer
     * supports (e.g. leaving PPLUL selected after dropping from 5 to 3 days).
     */
    fun splitForDays(current: SplitTemplate, days: Int): SplitTemplate {
        val options = SplitDefaults.optionsFor(days)
        return if (current == options.default || current == options.alternative) current else options.default
    }

    /** Assembles the screen's full render state for one step of [answers]. */
    fun buildUiState(stepIndex: Int, answers: WizardAnswers, isComplete: Boolean): WizardUiState {
        val clamped = stepIndex.coerceIn(0, WizardStep.entries.lastIndex)
        return WizardUiState(
            stepIndex = clamped,
            step = WizardStep.entries[clamped],
            answers = answers,
            splitOptions = SplitDefaults.optionsFor(answers.daysPerWeek),
            activeAnchorIds = activeAnchorIds(answers),
            isComplete = isComplete,
        )
    }
}
