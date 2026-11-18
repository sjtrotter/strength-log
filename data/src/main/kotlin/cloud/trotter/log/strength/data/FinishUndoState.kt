package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.UndoPlanDto

enum class UndoResult { Undone, UndoneLadderKept, UndoneReplanKept, DayOpen, Nothing }

enum class ReplanAccept { Moved, RunOpen, Gone, Waiting }

data class FinishUndoState(
    val rough: Boolean,
    val hasStrengthRecord: Boolean,
    val restorable: Boolean,
    val strengthPlan: UndoPlanDto?,
    val roughLight: UndoPlanDto?,
    val cardioPlan: UndoPlanDto?,
)
