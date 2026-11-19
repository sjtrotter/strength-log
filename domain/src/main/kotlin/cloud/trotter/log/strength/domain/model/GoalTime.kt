package cloud.trotter.log.strength.domain.model

object GoalTime {
    /** The fastest time a goal over [metres] may set: for running, [metres] at 10 m/s, rounded down and at least one
     *  second (no real result is that fast, so "3:30" on a marathon reads as too fast, not 3 min 30 s); other
     *  activities need only a positive time. */
    fun floorSeconds(metres: Int, activity: CardioActivity): Int =
        if (CardioFamily.of(activity) == CardioFamily.RUN) (metres / 10).coerceAtLeast(1) else 1
}
