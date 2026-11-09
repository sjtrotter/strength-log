package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.LoggedSet

sealed interface Change {
    data object Hold : Change
    data class Increase(val fromLb: Double, val toLb: Double) : Change
    data class Decrease(val fromLb: Double, val toLb: Double) : Change
    data class RepsTo(val reps: Int) : Change
    data class Calibrated(val lb: Double) : Change
    data class KeptLowered(val lb: Double) : Change
    /** A layoff's scaled load became the working load. */
    data class Restart(val factor: Double) : Change
    data object LightCycle : Change
}

/** How a run opened after a gap. The run stores it and hands it back in [Progression.Exposure.layoff]. */
sealed interface LayoffKind {
    data object None : LayoffKind
    data object Hold : LayoffKind
    data class Scaled(val factor: Double) : LayoffKind
    data object Recalibrate : LayoffKind
}

/** [ruleOverride] is the rule to run the session with when it differs from the stored one (calibrating on after a
 *  long layoff). It is a view; nothing is written. */
data class Opening(val rows: List<LoggedSet>, val layoff: LayoffKind, val ruleOverride: SlotRule?)

data class Outcome(
    val rows: List<LoggedSet>,
    val rule: SlotRule,
    val change: Change,
    val reason: Reason,
)
