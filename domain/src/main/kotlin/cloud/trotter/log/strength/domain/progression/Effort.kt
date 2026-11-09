package cloud.trotter.log.strength.domain.progression

/** The four effort words and the reps in reserve each stands for. A null `LoggedSet.rir` means on target. */
enum class Effort(val rir: Int) { EASY(4), GOOD(2), HARD(1), MAX(0) }
