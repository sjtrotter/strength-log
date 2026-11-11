package cloud.trotter.log.strength.domain.units

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.Measure
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.roundToInt

/** Display unit for a cardio distance; storage is always whole metres.
 *  The setting is [MI] or [KM]; [M] is display-only for track and rower segments. */
enum class DistanceUnit(private val millimetresPerUnit: Int, val symbol: String) {
    MI(1_609_344, "mi"), KM(1_000_000, "km"), M(1_000, "m");

    private val metresPerUnit: Double = millimetresPerUnit / 1000.0

    fun fromMetres(metres: Int): Double = metres / metresPerUnit

    /** Whole hundredths for stepper bounds, using the units' exact millimetre conversion. */
    fun wholeHundredthsFromMetres(metres: Int): Long =
        metres.toLong() * 100_000 / millimetresPerUnit

    /** Typed value in this unit to whole metres, or null outside the [Measure.Distance] range. */
    fun toMetres(value: Double): Int? {
        if (!value.isFinite()) return null
        return (value * metresPerUnit).roundToInt().takeIf { it in 1..200_000 }
    }

    fun format(metres: Int): String = if (this == M) String.format(Locale.US, "%,d m", metres)
        else "${inputText(metres)} $symbol"

    /** The text to type back: display precision, no symbol or grouping. */
    fun inputText(metres: Int): String = if (this == M) metres.toString() else WeightStepper.format(
        BigDecimal.valueOf(fromMetres(metres)).setScale(2, RoundingMode.HALF_UP).toDouble(),
    )

    companion object {
        fun suggestedFor(weight: WeightUnit): DistanceUnit = if (weight == WeightUnit.KG) KM else MI

        /** Track and rower segments keep metres whatever the setting. */
        fun forActivity(setting: DistanceUnit, activity: CardioActivity?): DistanceUnit =
            if (activity == CardioActivity.TRACK_RUN || activity == CardioActivity.ROWER) M else setting
    }
}
