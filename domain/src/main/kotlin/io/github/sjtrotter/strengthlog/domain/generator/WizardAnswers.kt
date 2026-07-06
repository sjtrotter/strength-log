package io.github.sjtrotter.strengthlog.domain.generator

import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig

/** Which four main lifts anchor the rotation (spec §6.1 step 4). */
enum class AnchorScheme {
    /** Squat / Bench / Deadlift-variant / Incline DB — maintenance-friendly default. */
    PROTOTYPE,

    /** Squat / Bench / Deadlift-variant / Barbell Row — StrongLifts "big four". */
    BIG_4,

    /** Squat / Bench / Deadlift-variant / OHP — 5/3/1 lineage. */
    FIVE_THREE_ONE,
}

/** The deadlift anchor exposes its variant (spec §6.1 step 4, §12). */
enum class DeadliftVariant { TRAP_BAR, CONVENTIONAL, SUMO }

/** Concrete split the generator builds days from (spec §6.2). */
enum class SplitTemplate { FULL_BODY, UPPER_LOWER, PPL, PPLUL }

/**
 * Everything the setup wizard collects (spec §6.1). Every field has a default so
 * "next-next-next" yields the prototype 4-day full-body program: BALANCED goal,
 * 4 days, full-body split, prototype anchors with a trap-bar deadlift, outdoor-run
 * finishers, the reference lifter, and every piece of equipment available.
 */
data class WizardAnswers(
    val daysPerWeek: Int = 4,
    val split: SplitTemplate = SplitTemplate.FULL_BODY,
    val anchorScheme: AnchorScheme = AnchorScheme.PROTOTYPE,
    val deadliftVariant: DeadliftVariant = DeadliftVariant.TRAP_BAR,
    val cardio: CardioPrefs = CardioPrefs(),
    val config: LifterConfig = LifterConfig(),
    /** Optional equipment profile (PLAN.md A4). Defaults to everything; the
     *  generator filters slot picks down to what's available. */
    val equipment: Set<Equipment> = Equipment.entries.toSet(),
) {
    init {
        require(daysPerWeek in 2..6) { "daysPerWeek must be 2..6, was $daysPerWeek" }
    }

    val emphasis: GoalEmphasis get() = config.emphasis
}
