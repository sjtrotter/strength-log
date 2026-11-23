package cloud.trotter.log.strength.domain.standards

/**
 * A timed hold's clock, read the same way on the phone and the watch (#628): it counts whole seconds up from
 * the start, fills toward the target and ends at the target, or, past target, at [PAST_TARGET_LIMIT_SECONDS].
 * A set held once per side keeps both sides, left first, and logs its shorter side as the set's value.
 */
object HoldClock {
    /** A hold past its target ends itself here, so a forgotten clock cannot run on (#643). */
    const val PAST_TARGET_LIMIT_SECONDS = 600

    /** Where the clock ends: the target, or for a hold past target the limit (never before the target). */
    fun limitSeconds(targetSeconds: Int, pastTarget: Boolean): Int =
        if (pastTarget) maxOf(targetSeconds, PAST_TARGET_LIMIT_SECONDS) else targetSeconds

    fun heldSeconds(elapsedMillis: Long, limitSeconds: Int): Int =
        (elapsedMillis.coerceAtLeast(0L) / 1_000L).coerceAtMost(limitSeconds.toLong()).toInt()

    fun reached(elapsedMillis: Long, targetSeconds: Int): Boolean = elapsedMillis >= targetSeconds * 1_000L

    fun fraction(heldSeconds: Int, targetSeconds: Int): Float =
        if (targetSeconds <= 0) 0f else (heldSeconds.toFloat() / targetSeconds).coerceIn(0f, 1f)

    fun loggedSeconds(sides: List<Int>): Int = sides.minOrNull() ?: 0
}
