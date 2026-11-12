package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.UndoPlanDto

enum class UndoResult { Undone, DayOpen, Nothing }

data class FinishUndoState(
    val rough: Boolean,
    val hasStrengthRecord: Boolean,
    val restorable: Boolean,
    val strengthPlan: UndoPlanDto?,
    val roughLight: UndoPlanDto?,
    val cardioPlan: UndoPlanDto?,
)
