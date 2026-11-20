package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.DistanceUnit

/** The distance a pace is given per. Rowers think in 500 m splits; runners and walkers in their unit. */
enum class PacePer(val metres: Double, val label: String) {
    FIVE_HUNDRED_M(500.0, "500 m"), KM(1_000.0, "km"), MI(1_609.344, "mi"),
}

data class Pace(val seconds: Int, val per: PacePer) {
    val text: String get() = "${ClockText.duration(seconds)} /${per.label}"
}

object CardioPace {
    data class Effort(val seconds: Int, val metres: Int?)

    /** [setting] is the user's distance setting, never the activity's display unit. */
    fun per(activity: CardioActivity, setting: DistanceUnit): PacePer? = when (CardioFamily.of(activity)) {
        CardioFamily.ROW -> PacePer.FIVE_HUNDRED_M
        CardioFamily.RUN, CardioFamily.WALK -> if (setting == DistanceUnit.MI) PacePer.MI else PacePer.KM
        CardioFamily.RIDE, CardioFamily.ELLIPTICAL -> null
    }

    /** Totals only efforts with a distance; a pace beyond a day per unit is not shown. */
    fun of(activity: CardioActivity, efforts: List<Effort>, setting: DistanceUnit): Pace? {
        val per = per(activity, setting) ?: return null
        val measured = efforts.filter { it.metres != null }
        val metres = measured.sumOf { requireNotNull(it.metres).toLong() }
        if (metres <= 0L) return null
        val seconds = measured.sumOf { it.seconds.toLong() }
        val rounded = Math.round(seconds * per.metres / metres)
        return rounded.takeIf { it in 0L..86_400L }?.let { Pace(it.toInt(), per) }
    }

    /** Pace of performed WORK sets, including shortened work. */
    fun ofWork(activity: CardioActivity, results: List<CardioSetResult>, setting: DistanceUnit): Pace? =
        of(activity, results.filter { it.spec.role == CardioRole.WORK && it.status != CardioSetStatus.SKIPPED }
            .map { Effort(it.elapsedSeconds, it.metres) }, setting)
}
