package cloud.trotter.log.strength.domain.progression

/** How the set page reports effort (design §4.2): the four words, or reps left as a number. Stored by name; new
 *  constants go at the end. */
enum class EffortScale {
    WORDS, REPS_LEFT;

    companion object {
        /** The largest reps-left number the scale offers; also the bound on a stored rir. */
        const val MAX_REPS_LEFT = 5
    }
}
