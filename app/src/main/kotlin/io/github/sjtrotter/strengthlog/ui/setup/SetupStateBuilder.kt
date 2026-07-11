package io.github.sjtrotter.strengthlog.ui.setup

import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.standards.GoalFormatter
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/**
 * Pure decision logic behind the setup screen (spec §8.4): the live main-lift
 * GOAL preview and the unit-aware bodyweight display. Kept free of Android/Hilt
 * so it is unit-testable on the JVM; [SetupViewModel] only wires this to
 * repository flows and writes.
 *
 * No goal math is reimplemented here (SSOT) — every GOAL comes straight from
 * `:domain`'s [GoalCalculator], the same function [io.github.sjtrotter.strengthlog.ui.day.DayViewModel]
 * calls for the day screen's GOAL block.
 */
object SetupStateBuilder {

    /**
     * The four main-lift GOALs for the stored wizard [answers]' anchor scheme
     * and deadlift variant (spec §11's squat/bench/deadlift/accessory numbers),
     * recomputed against [cfg] and formatted for [unit]. Setup doesn't let the
     * lifter change which lifts anchor the program — only the wizard does that
     * (D1) — so the ids come straight from [ProgramGenerator.anchorIds], the
     * same four lifts the generated program itself uses.
     */
    fun goalPreview(cfg: LifterConfig, answers: WizardAnswers, unit: WeightUnit): List<GoalPreviewItem> =
        ProgramGenerator.anchorIds(answers).map { id ->
            val entry = ExerciseLibrary.get(id)
            GoalPreviewItem(
                name = entry.name,
                display = GoalFormatter.label(GoalCalculator.targetFor(entry, cfg), unit),
                perHand = entry.perHand,
            )
        }

    /** [cfg]'s canonical-lb bodyweight converted to [unit] for the stepper's display value. */
    fun bodyweightDisplay(cfg: LifterConfig, unit: WeightUnit): Double =
        unit.fromLb(cfg.bodyweightLb.toDouble())

    /** Converts a stepper's [unit]-display value back to the whole-pound canonical storage
     *  ([LifterConfig.bodyweightLb] is an Int — spec goal math is lb-integer-calibrated). */
    fun bodyweightLb(displayValue: Double, unit: WeightUnit): Int =
        Math.round(unit.toLb(displayValue)).toInt()

    /** Assembles the screen's full render state off the current config/prefs/answers. */
    fun buildUiState(
        cfg: LifterConfig,
        cardio: CardioPrefs,
        unit: WeightUnit,
        answers: WizardAnswers,
    ): SetupUiState = SetupUiState(
        config = cfg,
        cardio = cardio,
        unit = unit,
        bodyweightDisplay = bodyweightDisplay(cfg, unit),
        goalPreview = goalPreview(cfg, answers, unit),
    )
}
