package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.UndoPlanDto

enum class UndoResult { Undone, UndoneLadderKept, UndoneReplanKept, DayOpen, Nothing }

enum class ReplanAccept { Moved, RunOpen, Gone, Waiting }

/** A follower row this Finish moved: its day and working load before and after. */
data class FollowedWrite(val dayId: String, val fromLb: Double?, val toLb: Double)

data class FinishUndoState(
    val rough: Boolean,
    val hasStrengthRecord: Boolean,
    val restorable: Boolean,
    val strengthPlan: UndoPlanDto?,
    val roughLight: UndoPlanDto?,
    val cardioPlan: UndoPlanDto?,
)
