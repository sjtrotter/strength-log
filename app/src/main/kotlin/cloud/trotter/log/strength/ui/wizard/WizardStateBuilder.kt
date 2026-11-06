package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.SplitDefaults
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.seeding.SlotSeeds
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.ui.text.UiText

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

    fun previewLifts(program: Program?, config: LifterConfig, unit: WeightUnit): Map<String, List<WizardPreviewLift>> =
        program?.days.orEmpty().associate { day ->
            day.id to day.exercises.map { pe ->
                val entry = ExerciseLibrary.get(pe.exerciseId)
                val name = entry.name + pe.superset?.let { " + " + ExerciseLibrary.get(it.exerciseId).name }.orEmpty()
                val rows = SlotSeeds.main(pe, entry, config)
                WizardPreviewLift(
                    name = name,
                    setCount = rows?.size ?: pe.targetSets,
                    detail = rows?.let {
                        DayScreenBuilder.workingHeading(it, pe.prescription?.tracking ?: entry.tracking, unit)
                    },
                )
            }
        }

    /** Assembles the screen's full render state for one step of [answers]. */
    fun buildUiState(
        stepIndex: Int,
        answers: WizardAnswers,
        isComplete: Boolean,
        restore: WizardRestoreState = WizardRestoreState(),
        unit: WeightUnit = WeightUnit.LB,
        previewProgram: Program? = null,
        finishError: UiText? = null,
        finishInFlight: Boolean = false,
        initialized: Boolean = true,
        bodyweightLb: Double? = null,
        age: Int? = null,
    ): WizardUiState {
        val clamped = stepIndex.coerceIn(0, WizardStep.entries.lastIndex)
        return WizardUiState(
            stepIndex = clamped,
            step = WizardStep.entries[clamped],
            answers = answers,
            splitOptions = SplitDefaults.optionsFor(answers.daysPerWeek),
            activeAnchorIds = ProgramGenerator.activeAnchorIds(answers),
            unit = unit,
            previewProgram = previewProgram,
            previewLifts = previewLifts(previewProgram, answers.config, unit),
            isComplete = isComplete,
            restore = restore,
            finishError = finishError,
            finishInFlight = finishInFlight,
            initialized = initialized,
            bodyweightLb = bodyweightLb,
            age = age,
        )
    }
}
