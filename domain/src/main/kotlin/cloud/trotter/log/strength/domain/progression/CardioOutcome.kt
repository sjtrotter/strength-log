package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.ProgressMeasure

/** What a cardio Finish changed; the receipt reads it (PR 2c). */
sealed interface CardioChange {
    /** The block runs again as it was. */
    data object Repeat : CardioChange
    /** A16's increase in [measure]; [capped] when the run cap cut it short. */
    data class Increase(val measure: ProgressMeasure, val capped: Boolean) : CardioChange
    data class LevelTo(val level: Int) : CardioChange
    /** Level 12 finished: the day leaves the ladder and runs A16 under the run cap from now on. */
    data object LadderDone : CardioChange
}

/** Next time's block and rule for a cardio day; the caller writes both together. */
data class CardioOutcome(val block: CardioBlock, val rule: CardioRule?, val change: CardioChange)
