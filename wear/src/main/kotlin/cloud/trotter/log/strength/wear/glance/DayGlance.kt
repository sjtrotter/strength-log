package cloud.trotter.log.strength.wear.glance

import cloud.trotter.log.strength.domain.glance.DayProgress
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
 * sets only (the same rule the dial's day ring uses), and the lines come from
 * localized resources that follow [cloud.trotter.log.strength.domain.glance.GlanceLines],
 * so one day reads the same on the wrist and on the home screen.
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
    val nothingOnToday: Boolean = false,
) {

    val done: Boolean get() = hasProgram && totalSets > 0 && doneSets >= totalSets

    /** The day ring's fill, 0..1. */
    val progress: Float
        get() = if (totalSets <= 0) 0f else (doneSets.toFloat() / totalSets).coerceIn(0f, 1f)

    /**
     * "Day A, Lower". With no program this is the dial's own wording rather than
     * the widget's "Set up a plan" — that is an instruction you can't follow
     * on a watch, and the tile is a mini version of a dial that already says this.
     */
    fun titleLine(copy: DayGlanceCopy): String = if (nothingOnToday) copy.nothingOnToday
    else if (!hasProgram) copy.noProgram
    else if (dayTitle.isBlank()) copy.dayOnly(dayLetter)
    else copy.dayTitle(dayLetter, dayTitle)

    /** The hero line: "3 lifts, 21 sets", "12 of 21 sets", "Done, 21 sets". */
    fun setLine(copy: DayGlanceCopy): String = when {
        nothingOnToday -> ""
        !hasProgram -> copy.setUpOnPhone
        done -> copy.doneSets(totalSets)
        doneSets > 0 -> copy.progressSets(doneSets, totalSets)
        else -> copy.liftsSets(exerciseCount, totalSets)
    }

    /** The complication's short text: the day letter, or an em dash with no program. */
    fun shortText(copy: DayGlanceCopy): String = when {
        nothingOnToday -> ""
        hasProgram -> dayLetter.uppercase()
        else -> copy.emptyShortText
    }

    /** The SHORT_TEXT complication's title, "12/21"; blank with no program. */
    fun ratioText(copy: DayGlanceCopy): String = if (hasProgram) copy.ratio(doneSets, totalSets) else ""

    /**
     * RANGED_VALUE's ceiling. Never 0: a range needs room to move, and an empty
     * day still has to render as an untouched ring rather than a divide by zero.
     */
    val rangeMax: Float get() = totalSets.coerceAtLeast(1).toFloat()

    val rangeValue: Float get() = doneSets.coerceIn(0, totalSets).toFloat()

    /** What a screen reader says — the complication has no other way to explain itself. */
    fun contentDescription(copy: DayGlanceCopy): String = when {
        nothingOnToday -> copy.nothingOnToday
        !hasProgram -> copy.noProgramDescription
        done -> copy.doneDescription(dayLetter, totalSets)
        else -> copy.progressDescription(dayLetter, doneSets, totalSets)
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
            if (day.dayId.isBlank()) return NONE.copy(nothingOnToday = true)
            val progress = DayProgress.of(snapshot)
            // A day with no sets is nothing to glance at, but it still has an accent —
            // keep it so the surface doesn't flip to day A's orange mid-rotation.
            if (progress.total == 0) return NONE.copy(accentIndex = day.accentIndex)
            return DayGlance(
                hasProgram = true,
                dayLetter = day.dayId,
                dayTitle = day.title,
                accentIndex = day.accentIndex,
                exerciseCount = day.exercises.size,
                doneSets = progress.done,
                totalSets = progress.total,
            )
        }
    }
}

data class DayGlanceCopy(
    val noProgram: String,
    val setUpOnPhone: String,
    val dayTitle: (String, String) -> String,
    val dayOnly: (String) -> String,
    val liftsSets: (Int, Int) -> String,
    val progressSets: (Int, Int) -> String,
    val doneSets: (Int) -> String,
    val noProgramDescription: String,
    val doneDescription: (String, Int) -> String,
    val progressDescription: (String, Int, Int) -> String,
    val emptyShortText: String,
    val ratio: (Int, Int) -> String,
    val nothingOnToday: String,
)
