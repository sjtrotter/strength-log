package cloud.trotter.log.strength.domain.model

/** A plan's one optional goal (#497). [byEpochDay] is the optional target date as LocalDate.toEpochDay(). */
sealed interface PlanGoal {
    val byEpochDay: Long?

    /** Cover [metres] of [activity] in strictly less than [underSeconds]. */
    data class Timed(
        val activity: CardioActivity,
        val metres: Int,
        val underSeconds: Int,
        override val byEpochDay: Long? = null,
    ) : PlanGoal {
        init {
            require(metres in 1..200_000)
            require(underSeconds in 1..86_400)
        }
    }

    /** Reach [targetShuttles] on a shuttle test named [protocol], entered by hand. */
    data class Shuttle(
        val protocol: String,
        val targetShuttles: Int,
        val attempts: List<ShuttleAttempt> = emptyList(),
        override val byEpochDay: Long? = null,
    ) : PlanGoal {
        init {
            require(protocol.trim().length in 1..30)
            require(targetShuttles in 1..999)
        }
    }

    /** Run [metres] fast (design §3.1.3). [attempts] are time-trial bests, each compared only within its own distance,
     *  timing method and start protocol; [targetCentis] is optional (no setup question asks it). */
    data class Sprint(
        val metres: Int,
        val targetCentis: Int? = null,
        val attempts: List<SprintAttempt> = emptyList<SprintAttempt>(),
        override val byEpochDay: Long? = null,
    ) : PlanGoal {
        init {
            require(metres in 1..1_000)
            require(targetCentis == null || targetCentis in 1..SprintTime.MAX_CENTIS)
        }
    }
}

data class ShuttleAttempt(val completedAt: Long, val shuttles: Int) {
    init { require(shuttles in 0..999) }
}

data class SprintAttempt(val completedAt: Long, val time: SprintTime)
