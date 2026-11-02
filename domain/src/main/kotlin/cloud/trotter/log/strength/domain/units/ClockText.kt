package cloud.trotter.log.strength.domain.units

object ClockText {
    fun minutesSeconds(seconds: Int): String =
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
