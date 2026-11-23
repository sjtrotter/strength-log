package cloud.trotter.log.strength.domain.progression

/** Four effort words stored in rir; on holds they describe perceived effort, not time remaining. */
enum class Effort(val rir: Int) {
    EASY(4), GOOD(2), HARD(1), MAX(0);

    companion object {
        fun of(rir: Int?): Effort? = when {
            rir == null -> null
            rir >= EASY.rir -> EASY
            else -> entries.firstOrNull { it.rir == rir }
        }
    }
}
