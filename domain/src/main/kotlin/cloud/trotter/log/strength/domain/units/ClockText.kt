package cloud.trotter.log.strength.domain.units

object ClockText {
    /** m:ss under an hour, h:mm:ss from an hour: 52:07, 4:00:49. */
    fun duration(seconds: Int): String = if (seconds < 3600) minutesSeconds(seconds)
        else "${seconds / 3600}:${minutesSeconds(seconds % 3600).padStart(5, '0')}"

    /** m:ss or h:mm:ss; trailing minutes and seconds must be below 60. */
    fun parse(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.any { char -> char !in '0'..'9' } }) return null
        val values = parts.map { it.toLongOrNull() ?: return null }
        if (values.last() >= 60 || parts.size == 3 && values[1] >= 60) return null
        var seconds = 0L
        for (value in values) {
            if (value > Int.MAX_VALUE || seconds > (Int.MAX_VALUE - value) / 60) return null
            seconds = seconds * 60 + value
        }
        return seconds.toInt()
    }

    fun minutesSeconds(seconds: Int): String =
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
