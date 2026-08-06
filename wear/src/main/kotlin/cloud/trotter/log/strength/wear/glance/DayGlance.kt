package cloud.trotter.log.strength.wear.glance

import cloud.trotter.log.strength.domain.sync.WatchSnapshot

/**
 * Today, reduced to what a glance can hold. The tile and the watch-face
 * complication (glance-surfaces brief §2/§3) render from this and nothing else,
 * so every state they can show is decided in one pure function and pinned by
 * JVM tests.
 *
 * "Glance" here is the brief's word for these read-only surfaces — not Jetpack
 * Glance, which this app deliberately doesn't use.
 *
 * Two facts are borrowed rather than re-decided: progress counts the main track's
 * sets only (the same rule the dial's day ring uses), and the lines are character
 * for character the phone widget's [cloud.trotter.log.strength.widget]
 * `todayWidgetContent`, so one day reads identically on the wrist and on the home
 * screen. That parity is currently held by hand — the two builders live in
 * modules that share only `:domain`, and hoisting the line logic there is a
 * follow-up worth doing now that both surfaces exist.
 */
data class DayGlance(
    val hasProgram: Boolean,
    /** "A", "B", … — blank with no program. */
    val dayLetter: String,
    /**
     * The day's short title — "Lower", "Full Body". Deliberately not
     * [cloud.trotter.log.strength.domain.sync.WatchDay.emphasisLine], which is a
     * five-clause sentence ("flat press · vertical pull · …"); the widget made the
     * same call, and a tile's centre has even less room than a widget row.
     */
    val dayTitle: String,
    val accentIndex: Int,
    val exerciseCount: Int,
    val doneSets: Int,
    val totalSets: Int,
) {

    val done: Boolean get() = hasProgram && totalSets > 0 && doneSets >= totalSets

    /** The day ring's fill, 0..1. */
    val progress: Float
        get() = if (totalSets <= 0) 0f else (doneSets.toFloat() / totalSets).coerceIn(0f, 1f)

    /**
     * "DAY A · LOWER". With no program this is the dial's own wording rather than
     * the widget's "SET UP YOUR PROGRAM" — that is an instruction you can't follow
     * on a watch, and the tile is a mini version of a dial that already says this.
     */
    val titleLine: String
        get() = if (!hasProgram) {
            "no program".uppercase()
        } else {
            listOf("day $dayLetter", dayTitle).filter { it.isNotBlank() }.joinToString(" · ").uppercase()
        }

    /** The hero line: "3 LIFTS · 21 SETS", "12 / 21 SETS", "DONE · 21 SETS". */
    val setLine: String
        get() = when {
            !hasProgram -> "SET UP ON YOUR PHONE"
            done -> "DONE · ${count(totalSets, "SET")}"
            doneSets == 0 -> "${count(exerciseCount, "LIFT")} · ${count(totalSets, "SET")}"
            else -> "$doneSets / ${count(totalSets, "SET")}"
        }

    /** The complication's short text: the day letter, or an em dash with no program. */
    val shortText: String get() = if (hasProgram) dayLetter.uppercase() else "—"

    /** The SHORT_TEXT complication's title, "12/21"; blank with no program. */
    val ratioText: String get() = if (hasProgram) "$doneSets/$totalSets" else ""

    /**
     * RANGED_VALUE's ceiling. Never 0: a range needs room to move, and an empty
     * day still has to render as an untouched ring rather than a divide by zero.
     */
    val rangeMax: Float get() = totalSets.coerceAtLeast(1).toFloat()

    val rangeValue: Float get() = doneSets.coerceIn(0, totalSets).toFloat()

    /** What a screen reader says — the complication has no other way to explain itself. */
    val contentDescription: String
        get() = when {
            !hasProgram -> "No program yet"
            done -> "Day $dayLetter done, $totalSets sets"
            else -> "Day $dayLetter, $doneSets of $totalSets sets done"
        }

    companion object {

        /** Nothing to show — no snapshot has landed, or the day has no work in it. */
        val NONE = DayGlance(
            hasProgram = false,
            dayLetter = "",
            dayTitle = "",
            accentIndex = 0,
            exerciseCount = 0,
            doneSets = 0,
            totalSets = 0,
        )

        fun of(snapshot: WatchSnapshot?): DayGlance {
            val day = snapshot?.day ?: return NONE
            val sets = day.exercises.flatMap { it.sets }
            // A day with no sets is nothing to glance at, but it still has an accent —
            // keep it so the surface doesn't flip to day A's orange mid-rotation.
            if (sets.isEmpty()) return NONE.copy(accentIndex = day.accentIndex)
            return DayGlance(
                hasProgram = true,
                dayLetter = day.dayId,
                dayTitle = day.title,
                accentIndex = day.accentIndex,
                exerciseCount = day.exercises.size,
                doneSets = sets.count { it.done },
                totalSets = sets.size,
            )
        }
    }
}

/** "1 LIFT", "3 LIFTS" — the widget's pluraliser, kept in step with it by hand. */
private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}S"
