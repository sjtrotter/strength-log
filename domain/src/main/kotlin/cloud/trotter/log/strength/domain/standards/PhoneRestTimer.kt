package cloud.trotter.log.strength.domain.standards

/** Persistable phone-rest state. Epoch time is intentional: unlike the watch's
 * process-local elapsed-realtime timer, this countdown must survive process death. */
data class PhoneRest(
    val deadlineEpochMillis: Long,
    val totalSeconds: Int,
    val nextSetLabel: String,
)

/** Pure phone timer rules; Android code only persists and presents the result. */
object PhoneRestTimer {
    fun start(nowEpochMillis: Long, seconds: Int, nextSetLabel: String): PhoneRest? =
        seconds.coerceAtLeast(0).takeIf { it > 0 }?.let {
            PhoneRest(nowEpochMillis + it * 1_000L, it, nextSetLabel)
        }

    fun remainingMillis(rest: PhoneRest, nowEpochMillis: Long): Long =
        (rest.deadlineEpochMillis - nowEpochMillis).coerceAtLeast(0L)

    fun remainingSeconds(rest: PhoneRest, nowEpochMillis: Long): Int =
        ((remainingMillis(rest, nowEpochMillis) + 999L) / 1_000L).toInt()

    fun remainingFraction(rest: PhoneRest, nowEpochMillis: Long): Float =
        if (rest.totalSeconds <= 0) 0f else
            (remainingMillis(rest, nowEpochMillis).toFloat() / (rest.totalSeconds * 1_000f)).coerceIn(0f, 1f)

    fun adjust(rest: PhoneRest, nowEpochMillis: Long, deltaSeconds: Int): PhoneRest? {
        val adjusted = (remainingMillis(rest, nowEpochMillis) + deltaSeconds * 1_000L).coerceAtLeast(0L)
        if (adjusted == 0L) return null
        return rest.copy(
            deadlineEpochMillis = nowEpochMillis + adjusted,
            totalSeconds = ((adjusted + 999L) / 1_000L).toInt(),
        )
    }

    fun skip(): PhoneRest? = null
}
