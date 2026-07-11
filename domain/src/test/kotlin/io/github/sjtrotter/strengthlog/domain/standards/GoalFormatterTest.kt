package io.github.sjtrotter.strengthlog.domain.standards

import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The SSOT GOAL formatter. WEIGHTED output must stay byte-identical to the old
 * `WeightStepper.format(unit.fromLb(goalLb))` path; REPS/TIMED must render a
 * self-describing string instead of ever throwing or printing "0 lb × 60".
 */
class GoalFormatterTest {

    private val cfg = LifterConfig() // pinned §11 lifter: 235/40/INTERMEDIATE/BALANCED

    // --- WEIGHTED: byte-identical to the pre-refactor number ------------------

    @Test
    fun `weighted label is the bare unit-converted number, no per-hand suffix`() {
        assertEquals("235", GoalFormatter.label(GoalTarget.Weight(235.0, perHand = false), WeightUnit.LB))
        // per-hand does not change the string — "/hand" stays a per-surface line.
        assertEquals("75", GoalFormatter.label(GoalTarget.Weight(75.0, perHand = true), WeightUnit.LB))
    }

    @Test
    fun `weighted label matches the old WeightStepper path for both units`() {
        for (lb in listOf(5.0, 60.0, 175.0, 235.0, 245.0)) {
            for (unit in WeightUnit.values()) {
                assertEquals(
                    WeightStepper.format(unit.fromLb(lb)),
                    GoalFormatter.label(GoalTarget.Weight(lb, perHand = false), unit),
                )
            }
        }
    }

    @Test
    fun `squat GOAL formats to the pinned 235 through targetFor plus the formatter`() {
        val squat = GoalCalculator.targetFor(ExerciseLibrary.get("bb_back_squat"), cfg)
        assertEquals("235", GoalFormatter.label(squat, WeightUnit.LB))
    }

    // --- REPS / TIMED: self-describing, never a crash ------------------------

    @Test
    fun `reps label reads the rep target`() {
        assertEquals("6 reps", GoalFormatter.label(GoalTarget.Reps(6), WeightUnit.LB))
    }

    @Test
    fun `timed label reads seconds, with converted added load when present`() {
        assertEquals("45s", GoalFormatter.label(GoalTarget.Time(45, 0.0), WeightUnit.LB))
        assertEquals("45s +25", GoalFormatter.label(GoalTarget.Time(45, 25.0), WeightUnit.LB))
        // 44.092452436 lb == exactly 20 kg: an even round trip, so the added-load
        // number isn't sensitive to WeightStepper's decimal formatting.
        assertEquals("30s +20", GoalFormatter.label(GoalTarget.Time(30, 44.092452436), WeightUnit.KG))
    }

    @Test
    fun `timed label switches to m colon ss at 90 seconds, same threshold as the row stepper`() {
        assertEquals("1:30", GoalFormatter.label(GoalTarget.Time(90, 0.0), WeightUnit.LB))
        assertEquals("2:00 +45", GoalFormatter.label(GoalTarget.Time(120, 45.0), WeightUnit.LB))
    }
}
