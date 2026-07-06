package io.github.sjtrotter.strengthlog.domain.standards

import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.StandardLift

/**
 * Calibrated in the prototype. Do not change without re-running the spec §11
 * pinned verification numbers.
 */
object StrengthStandards {
    // Estimated 1RM as multiple of bodyweight, by level. ROW uses ~0.75 of BENCH ratio.
    val ratios: Map<ExperienceLevel, Map<StandardLift, Double>> = mapOf(
        ExperienceLevel.NOVICE to mapOf(
            StandardLift.SQUAT to 1.25, StandardLift.BENCH to 1.0,
            StandardLift.DEADLIFT to 1.5, StandardLift.OHP to 0.6,
            StandardLift.INCLINE to 0.85, StandardLift.ROW to 0.75,
        ),
        ExperienceLevel.INTERMEDIATE to mapOf(
            StandardLift.SQUAT to 1.5, StandardLift.BENCH to 1.25,
            StandardLift.DEADLIFT to 2.0, StandardLift.OHP to 0.8,
            StandardLift.INCLINE to 1.05, StandardLift.ROW to 0.95,
        ),
        ExperienceLevel.ADVANCED to mapOf(
            StandardLift.SQUAT to 2.0, StandardLift.BENCH to 1.75,
            StandardLift.DEADLIFT to 2.5, StandardLift.OHP to 1.1,
            StandardLift.INCLINE to 1.45, StandardLift.ROW to 1.3,
        ),
    )

    // Per-lift correction so calculated working weights are realistic:
    // trap-bar DL trimmed vs straight-bar 2.0x standard; DB incline trimmed vs barbell.
    val liftTune: Map<StandardLift, Double> = mapOf(
        StandardLift.SQUAT to 1.0, StandardLift.BENCH to 1.0,
        StandardLift.DEADLIFT to 0.82, StandardLift.OHP to 1.0,
        StandardLift.INCLINE to 0.9, StandardLift.ROW to 1.0,
    )

    // Maintenance factor = working top set as a fraction of estimated 1RM.
    // Sits deliberately BELOW rep-max tables (5RM is ~87% 1RM; we want comfort).
    fun maintenanceFactor(e: GoalEmphasis) = when (e) {
        GoalEmphasis.STRENGTH -> 0.75 // top set of 3
        GoalEmphasis.BALANCED -> 0.68 // top set of 5  (prototype default)
        GoalEmphasis.PHYSIQUE -> 0.60 // top set of 8
    }

    fun topSetReps(e: GoalEmphasis) = when (e) {
        GoalEmphasis.STRENGTH -> 3
        GoalEmphasis.BALANCED -> 5
        GoalEmphasis.PHYSIQUE -> 8
    }

    const val BACKOFF = 0.75 // back-off weight as fraction of top set
    const val BACKOFF_REPS = 8
    val RAMP_PCTS = listOf(0.55, 0.70, 0.80, 0.90) // last ramp done as a lighter triple

    // Age de-rating: ~5% per decade after 40 (research). Continuous form that
    // reproduces the prototype's 0.97 at age 40 exactly:
    fun ageDerate(age: Int): Double =
        (1.0 - 0.005 * maxOf(0, age - 34)).coerceIn(0.80, 1.00)
}
