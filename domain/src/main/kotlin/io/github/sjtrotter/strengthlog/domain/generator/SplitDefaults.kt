package io.github.sjtrotter.strengthlog.domain.generator

/** A single training day's shape (spec §6.3). */
enum class DayKind { FULL_BODY, UPPER, LOWER, PUSH, PULL, LEGS }

/**
 * The §6.2 split table: the sane default plus the offered alternative per
 * days/week, and the concrete day sequence each split expands to. All splits are
 * rotation-based (advance on completion, principle 1).
 */
object SplitDefaults {

    data class Options(val default: SplitTemplate, val alternative: SplitTemplate?)

    fun optionsFor(days: Int): Options = when (days) {
        2 -> Options(SplitTemplate.FULL_BODY, null)
        3 -> Options(SplitTemplate.FULL_BODY, SplitTemplate.PPL)
        4 -> Options(SplitTemplate.FULL_BODY, SplitTemplate.UPPER_LOWER)
        5 -> Options(SplitTemplate.PPLUL, SplitTemplate.FULL_BODY)
        6 -> Options(SplitTemplate.PPL, SplitTemplate.UPPER_LOWER)
        else -> error("daysPerWeek must be 2..6, was $days")
    }

    fun defaultFor(days: Int): SplitTemplate = optionsFor(days).default

    fun alternativeFor(days: Int): SplitTemplate? = optionsFor(days).alternative

    /** Ordered day kinds a [split] expands to for [days] rotation slots. */
    fun dayKinds(split: SplitTemplate, days: Int): List<DayKind> = when (split) {
        SplitTemplate.FULL_BODY -> List(days) { DayKind.FULL_BODY }
        SplitTemplate.UPPER_LOWER ->
            List(days) { if (it % 2 == 0) DayKind.UPPER else DayKind.LOWER }
        SplitTemplate.PPL -> {
            val cycle = listOf(DayKind.PUSH, DayKind.PULL, DayKind.LEGS)
            List(days) { cycle[it % cycle.size] }
        }
        // The 5-day hybrid is a fixed sequence regardless of the requested count.
        SplitTemplate.PPLUL ->
            listOf(DayKind.PUSH, DayKind.PULL, DayKind.LEGS, DayKind.UPPER, DayKind.LOWER)
    }
}
