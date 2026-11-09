package cloud.trotter.log.strength.domain.progression

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

/** The cardio log for one day's family, as the caller reads it at run open or Finish (PR 2b builds it). */
data class CardioHistory(
    /** [RunCap.maxima] over the family's finished sessions of the last 30 days; null when there are none. */
    val longest: RecentMaxima? = null,
    /** Seconds per metre of the family's most recent finished session with a metres total. */
    val secondsPerMetre: Double? = null,
    /** The setup answer's longest run (Where you are: a distance or minutes), standing in while there is no history. */
    val seed: RecentMaxima? = null,
    /** The day the plan was created; the seed stands in for 30 days from it. */
    val planCreatedEpochDay: Long? = null,
)
