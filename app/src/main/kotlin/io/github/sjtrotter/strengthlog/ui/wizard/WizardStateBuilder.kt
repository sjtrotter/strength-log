package io.github.sjtrotter.strengthlog.ui.wizard

import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.SplitDefaults
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers

/**
 * The pure decision logic behind the wizard: which split stays valid when the
 * day count changes and the per-step [WizardUiState] snapshot. Kept free of
 * Android/Hilt so it is unit-testable on the JVM; [WizardViewModel] only wires
 * this to [androidx.lifecycle.SavedStateHandle] and repository writes. No
 * generator math is reimplemented here: the active anchor list comes straight
 * from [ProgramGenerator.activeAnchorIds] (SSOT — the same narrowing the
 * generated program uses), and split options from [SplitDefaults].
 */
object WizardStateBuilder {

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
            activeAnchorIds = ProgramGenerator.activeAnchorIds(answers),
            isComplete = isComplete,
        )
    }
}
