package cloud.trotter.log.strength.domain.units

import cloud.trotter.log.strength.domain.model.SprintTime

object ClockText {
    /** Seconds and hundredths under a minute, m:ss.hh from a minute. */
    fun centis(centis: Int): String {
        val seconds = centis / 100
        val whole = if (seconds < 60) seconds.toString() else minutesSeconds(seconds)
        return "$whole.${(centis % 100).toString().padStart(2, '0')}"
    }

    /** Decimal seconds or m:ss.hh, positive and within the sprint time limit. */
    fun parseCentis(text: String): Int? {
        val match = Regex("""(?:(\d+):([0-5]\d)|(\d+))(?:\.(\d{1,2}))?""").matchEntire(text.trim()) ?: return null
        val minutes = match.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: return null
        val seconds = (match.groupValues[2].ifEmpty { match.groupValues[3] }).toLongOrNull() ?: return null
        val limit = SprintTime.MAX_CENTIS.toLong()
        if (minutes > limit / 6000 || seconds > limit / 100) return null
        val fraction = match.groupValues[4].padEnd(2, '0').toInt()
        return (minutes * 6000 + seconds * 100 + fraction).takeIf { it in 1..limit }?.toInt()
    }

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
