package cloud.trotter.log.strength.domain.glance

/**
 * SSOT for the two lines a glance surface prints (glance-surfaces.md §1): the
 * phone widget and receipt read them here. The watch's localized tile resources
 * follow the same wording, with a parity test against this contract, so one day
 * says the same thing on the home screen and on the wrist.
 *
 * What a surface still decides for itself is its no-program wording — "set up
 * your program" is an instruction you can follow on a phone and not on a watch.
 */
object GlanceLines {

    /** "Day A, Squat focus", or just "Day A" when the day has no title (blank or whitespace). */
    fun dayLine(dayId: String, title: String): String =
        listOf("Day $dayId", title).filter { it.isNotBlank() }.joinToString(", ")

    /**
     * "3 lifts, 21 sets" before the first set, "12 of 21 sets" during, "Done, 21 sets" after.
     *
     * A day counts as done at `doneSets >= totalSets`, not `==`: this formats counts it
     * doesn't derive, and an over-count is better read as a finished day than as "22 / 21".
     */
    fun statLine(exerciseCount: Int, doneSets: Int, totalSets: Int): String = when {
        totalSets > 0 && doneSets >= totalSets -> "Done, ${count(totalSets, "set")}"
        doneSets == 0 -> "${count(exerciseCount, "lift")}, ${count(totalSets, "set")}"
        else -> "$doneSets of ${count(totalSets, "set")}"
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
