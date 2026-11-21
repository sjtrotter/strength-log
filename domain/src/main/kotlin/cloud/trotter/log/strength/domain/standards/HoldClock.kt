package cloud.trotter.log.strength.domain.standards

/**
 * A timed hold's clock, read the same way on the phone and the watch (#628): it counts whole seconds up from
 * the start, fills toward the target and ends at the target. A set held once per side logs its shorter side.
 */
object HoldClock {
    fun heldSeconds(elapsedMillis: Long, targetSeconds: Int): Int =
        (elapsedMillis.coerceAtLeast(0L) / 1_000L).coerceAtMost(targetSeconds.toLong()).toInt()

    fun reached(elapsedMillis: Long, targetSeconds: Int): Boolean = elapsedMillis >= targetSeconds * 1_000L

    fun fraction(heldSeconds: Int, targetSeconds: Int): Float =
        if (targetSeconds <= 0) 0f else (heldSeconds.toFloat() / targetSeconds).coerceIn(0f, 1f)

    fun loggedSeconds(sides: List<Int>): Int = sides.minOrNull() ?: 0
}
