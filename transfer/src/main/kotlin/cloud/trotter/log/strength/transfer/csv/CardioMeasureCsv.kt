package cloud.trotter.log.strength.transfer.csv

import cloud.trotter.log.strength.domain.model.Measure

internal fun formatCardioMeasure(measure: Measure): String = when (measure) {
    is Measure.Distance -> "${measure.metres} m"
    is Measure.Time -> {
        val seconds = measure.seconds
        val tail = (seconds % 60).toString().padStart(2, '0')
        if (seconds < 3600) {
            "${seconds / 60}:$tail"
        } else {
            "${seconds / 3600}:${(seconds % 3600 / 60).toString().padStart(2, '0')}:$tail"
        }
    }
}

internal fun parseCardioMeasure(text: String): Measure {
    if (Regex("[0-9]+ m").matches(text)) {
        return Measure.Distance(text.removeSuffix(" m").toIntOrNull()
            ?: throw IllegalArgumentException("unparsable cardio distance measure '$text'"))
    }
    require(Regex("[0-9]+:[0-5][0-9]|[0-9]+:[0-5][0-9]:[0-5][0-9]").matches(text)) {
        "unparsable cardio measure '$text'"
    }
    val seconds = text.split(':').fold(0L) { total, part ->
        val value = part.toLongOrNull()
            ?: throw IllegalArgumentException("cardio time measure out of range '$text'")
        require(value <= Int.MAX_VALUE && total <= (Int.MAX_VALUE - value) / 60) { "cardio time measure out of range '$text'" }
        total * 60 + value
    }
    return Measure.Time(seconds.toInt())
}
