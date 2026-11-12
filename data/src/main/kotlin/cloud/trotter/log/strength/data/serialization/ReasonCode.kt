package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.progression.Reason

object ReasonCode {
    fun encode(reason: Reason): String = when (reason) {
        Reason.NoSets -> "NO_SETS"
        Reason.LightCycle -> "LIGHT_CYCLE"
        Reason.Layoff -> "LAYOFF"
        Reason.Calibrating -> "CALIBRATING"
        Reason.Calibrated -> "CALIBRATED"
        Reason.Lowered -> "LOWERED"
        Reason.AllMade -> "ALL_MADE"
        Reason.EasySession -> "EASY_SESSION"
        Reason.Streak -> "STREAK"
        Reason.MaxSet -> "MAX_SET"
        Reason.RepMiss -> "REP_MISS"
        Reason.Stall -> "STALL"
        Reason.EffortMiss -> "EFFORT_MISS"
        Reason.Incomplete -> "INCOMPLETE"
        Reason.AddWeight -> "ADD_WEIGHT"
        Reason.Timed -> "TIMED"
    }

    fun decode(code: String?): Reason? = when (code) {
        "NO_SETS" -> Reason.NoSets
        "LIGHT_CYCLE" -> Reason.LightCycle
        "LAYOFF" -> Reason.Layoff
        "CALIBRATING" -> Reason.Calibrating
        "CALIBRATED" -> Reason.Calibrated
        "LOWERED" -> Reason.Lowered
        "ALL_MADE" -> Reason.AllMade
        "EASY_SESSION" -> Reason.EasySession
        "STREAK" -> Reason.Streak
        "MAX_SET" -> Reason.MaxSet
        "REP_MISS" -> Reason.RepMiss
        "STALL" -> Reason.Stall
        "EFFORT_MISS" -> Reason.EffortMiss
        "INCOMPLETE" -> Reason.Incomplete
        "ADD_WEIGHT" -> Reason.AddWeight
        "TIMED" -> Reason.Timed
        else -> null
    }
}
