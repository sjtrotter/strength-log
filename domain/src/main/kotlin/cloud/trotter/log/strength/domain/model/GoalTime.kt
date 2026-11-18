package cloud.trotter.log.strength.domain.model

object GoalTime {
    fun floorSeconds(metres: Int, activity: CardioActivity): Int =
        if (CardioFamily.of(activity) == CardioFamily.RUN) (metres / 10).coerceAtLeast(1) else 1
}
