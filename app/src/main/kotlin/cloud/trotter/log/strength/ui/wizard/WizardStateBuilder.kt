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
import cloud.trotter.log.strength.domain.standards.StrengthStandards
import cloud.trotter.log.strength.domain.units.DistanceUnit
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

    fun steps(answers: WizardAnswers): List<WizardStep> = buildList {
        add(WizardStep.FOCUS)
        val cardio = answers.cardioFirst
        if (cardio != null) {
            addAll(listOf(WizardStep.CARDIO_ACTIVITY, WizardStep.CARDIO_START, WizardStep.CARDIO_SESSIONS, WizardStep.ALSO_LIFT))
        }
        if (cardio == null || cardio.liftDays > 0) {
            add(WizardStep.EMPHASIS)
            if (cardio == null) add(WizardStep.DAYS_PER_WEEK)
            addAll(listOf(WizardStep.SPLIT, WizardStep.ANCHORS))
            if (cardio == null) add(WizardStep.CARDIO)
            addAll(listOf(WizardStep.ABOUT_YOU, WizardStep.EQUIPMENT, WizardStep.LIFTS))
        }
        add(WizardStep.ROTATION)
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
        distanceUnit: DistanceUnit = DistanceUnit.MI,
        cardioStartByTime: Boolean = true,
        previewProgram: Program? = null,
        finishError: UiText? = null,
        finishInFlight: Boolean = false,
        initialized: Boolean = true,
        bodyweightLb: Double? = null,
        age: Int? = null,
        liftDrafts: List<WizardLiftDraft> = emptyList(),
    ): WizardUiState {
        val steps = steps(answers)
        val clamped = stepIndex.coerceIn(steps.indices)
        return WizardUiState(
            stepIndex = clamped,
            step = steps[clamped],
            steps = steps,
            answers = answers,
            splitOptions = SplitDefaults.optionsFor(answers.daysPerWeek),
            activeAnchorIds = ProgramGenerator.activeAnchorIds(answers),
            unit = unit,
            distanceUnit = distanceUnit,
            cardioStartByTime = cardioStartByTime,
            previewProgram = previewProgram,
            previewLifts = previewLifts(previewProgram, answers.config, unit),
            isComplete = isComplete,
            restore = restore,
            finishError = finishError,
            finishInFlight = finishInFlight,
            initialized = initialized,
            bodyweightLb = bodyweightLb,
            age = age,
            liftRows = ProgramGenerator.mainLiftIds(answers).map { id ->
                val entry = ExerciseLibrary.get(id)
                val draft = liftDrafts.firstOrNull { it.exerciseId == id }
                WizardLiftRow(id, entry.name, entry.perHand, draft?.weightLb, draft?.reps ?: StrengthStandards.topSetReps(answers.emphasis))
            },
        )
    }
}
