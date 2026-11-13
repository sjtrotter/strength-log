package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioRole
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
        const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** The largest 7 × 24 h total of the family's finished sessions in the last 30 days, metres and seconds
         *  each its own maximum; the phase gates' week form (design §3.1.2). 4b-ii passes it as KnownLoads.longestWeek. */
        fun longestWeek(sessions: List<LoggedCardio>, asOfMillis: Long): RecentMaxima? {
            val finished = sessions.filter { asOfMillis - it.completedAt in 0 until WINDOW_MILLIS }.mapNotNull { session ->
                RunCap.maxima(listOf(session.results))?.let { session.completedAt to it }
            }
            return finished.map { (end, _) ->
                val window = finished.filter { end - it.first in 0 until WEEK_MILLIS }.map { it.second }
                val metres = window.mapNotNull { it.metres }
                val seconds = window.mapNotNull { it.seconds }
                RecentMaxima(metres.takeIf { it.isNotEmpty() }?.sum(), seconds.takeIf { it.isNotEmpty() }?.sum())
            }.reduceOrNull { maximum, week -> maximum.max(week) }
        }

        /** The history a judgment reads at [asOfMillis]. [sessions] are the family's sessions completed before it,
         *  oldest first: [longest] spans those within [WINDOW_MILLIS], the pace the newest finished one with a metres
         *  total, whatever its age. No seed until PR 7. */
        fun of(sessions: List<LoggedCardio>, asOfMillis: Long): CardioHistory = CardioHistory(
            longest = RunCap.maxima(sessions.filter { asOfMillis - it.completedAt in 0 until WINDOW_MILLIS }.map { it.results }),
            secondsPerMetre = sessions.asReversed().firstNotNullOfOrNull { session ->
                val maxima = RunCap.maxima(listOf(session.results)) ?: return@firstNotNullOfOrNull null
                val metres = maxima.metres ?: return@firstNotNullOfOrNull null
                val seconds = session.results.filter { it.spec.role == CardioRole.WORK || it.spec.role == CardioRole.RECOVERY }
                    .sumOf { result ->
                        // With no distance reading at the end, only the totals form a matching pace pair.
                        if (result.secondsAtEnd != null && result.metresAtEnd == null) result.elapsedSeconds
                        else result.secondsAtEnd ?: result.elapsedSeconds
                    }
                if (metres > 0 && seconds > 0) seconds.toDouble() / metres else null
            },
        )
    }
}
