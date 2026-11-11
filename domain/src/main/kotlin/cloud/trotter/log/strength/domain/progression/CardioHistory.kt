package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioSetResult

/** One logged session for [CardioHistory.of]: when it completed and its set results. */
data class LoggedCardio(val completedAt: Long, val results: List<CardioSetResult>)

/** Per cardio family: the largest finished session total in metres and, independently, the largest in seconds, each
 *  from its own session, summed over the counted sets ([RunCap.maxima]). Null where no session had that total. */
data class RecentMaxima(val metres: Int?, val seconds: Int?) {
    init { require(metres != null || seconds != null) }

    /** Each maximum against [other]'s. */
    fun max(other: RecentMaxima): RecentMaxima = RecentMaxima(
        listOfNotNull(metres, other.metres).maxOrNull(),
        listOfNotNull(seconds, other.seconds).maxOrNull(),
    )
}

/** The cardio log for one day's family, as the caller reads it at run open or Finish ([of] builds it). */
data class CardioHistory(
    /** [RunCap.maxima] over the family's finished sessions of the last 30 days; null when there are none. */
    val longest: RecentMaxima? = null,
    /** Seconds per metre of the family's most recent finished session with a metres total. */
    val secondsPerMetre: Double? = null,
    /** The setup answer's longest run (Where you are: a distance or minutes), standing in while there is no history. */
    val seed: RecentMaxima? = null,
    /** The day the plan was created; the seed stands in for 30 days from it. */
    val planCreatedEpochDay: Long? = null,
) {
    companion object {
        const val WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** The history a judgment reads at [asOfMillis]. [sessions] are the family's sessions completed before it,
         *  oldest first: [longest] spans those within [WINDOW_MILLIS], the pace the newest finished one with a metres
         *  total, whatever its age. No seed until PR 7. */
        fun of(sessions: List<LoggedCardio>, asOfMillis: Long): CardioHistory = CardioHistory(
            longest = RunCap.maxima(sessions.filter { asOfMillis - it.completedAt in 0 until WINDOW_MILLIS }.map { it.results }),
            secondsPerMetre = sessions.asReversed().firstNotNullOfOrNull { session ->
                val maxima = RunCap.maxima(listOf(session.results)) ?: return@firstNotNullOfOrNull null
                val metres = maxima.metres ?: return@firstNotNullOfOrNull null
                val seconds = maxima.seconds ?: return@firstNotNullOfOrNull null
                if (metres > 0 && seconds > 0) seconds.toDouble() / metres else null
            },
        )
    }
}
