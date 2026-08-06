package cloud.trotter.log.strength.domain.glance

/**
 * SSOT for the two lines a glance surface prints (glance-surfaces.md §1): the
 * phone widget (`:app`) and the watch's tile and complication (`:wear`) both
 * read them here, so one day says the same thing on the home screen and on the
 * wrist. The two modules share only `:domain`, which is why this lives here and
 * not in either of them.
 *
 * What a surface still decides for itself is its no-program wording — "set up
 * your program" is an instruction you can follow on a phone and not on a watch.
 */
object GlanceLines {

    /** "DAY A · LOWER", or just "DAY A" when the day has no title (blank or whitespace). */
    fun dayLine(dayId: String, title: String): String =
        listOf("day $dayId", title).filter { it.isNotBlank() }.joinToString(" · ").uppercase()

    /**
     * "3 LIFTS · 21 SETS" before the first set, "12 / 21 SETS" during, "DONE · 21 SETS" after.
     *
     * A day counts as done at `doneSets >= totalSets`, not `==`: this formats counts it
     * doesn't derive, and an over-count is better read as a finished day than as "22 / 21".
     */
    fun statLine(exerciseCount: Int, doneSets: Int, totalSets: Int): String = when {
        totalSets > 0 && doneSets >= totalSets -> "DONE · ${count(totalSets, "SET")}"
        doneSets == 0 -> "${count(exerciseCount, "LIFT")} · ${count(totalSets, "SET")}"
        else -> "$doneSets / ${count(totalSets, "SET")}"
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}S"
}
