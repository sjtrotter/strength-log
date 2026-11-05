package cloud.trotter.log.strength.domain.units

object Bodyweight {
    fun round(value: Double): Double = Math.round(value * 10.0) / 10.0

    fun fromDisplay(value: Double, unit: WeightUnit): Double = round(unit.toLb(round(value)))

    fun display(lb: Double, unit: WeightUnit): Double = round(unit.fromLb(lb))
}
