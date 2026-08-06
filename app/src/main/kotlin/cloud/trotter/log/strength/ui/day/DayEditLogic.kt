package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.model.Equipment

/**
 * Pure search/filter over an already-ranked candidate list (spec §8.3, PLAN.md
 * A4). Candidates must arrive pre-ranked from [cloud.trotter.log.strength.data.catalog.ExerciseCatalog]'s
 * `substitutionsFor`/`byPattern` (subRank order, "best replacement first") —
 * this never re-sorts, it only narrows, so the ranked order always survives a
 * search or filter.
 */
object ExercisePicker {

    /**
     * [query] matches a case-insensitive substring of the entry's name (blank
     * query matches everything). [equipment] matches when every piece of gear
     * the entry needs is in the set — the same all-of rule
     * `ProgramGenerator.available` uses for generator picks, so "what this
     * lifter owns" means one thing across the app.
     */
    fun filter(
        candidates: List<ExerciseEntry>,
        query: String,
        equipment: Set<Equipment>,
    ): List<ExerciseEntry> {
        val q = query.trim()
        return candidates.filter { entry -> matchesQuery(entry, q) && matchesEquipment(entry, equipment) }
    }

    private fun matchesQuery(entry: ExerciseEntry, query: String): Boolean =
        query.isEmpty() || entry.name.contains(query, ignoreCase = true)

    private fun matchesEquipment(entry: ExerciseEntry, equipment: Set<Equipment>): Boolean =
        entry.equipment.all { it in equipment }
}

/** The day-edit sheet's remove rule (spec §8.3: minimum 3 exercises per day). */
object DayEditRules {
    const val MIN_EXERCISES_PER_DAY = 3

    fun canRemove(slotCount: Int): Boolean = slotCount > MIN_EXERCISES_PER_DAY
}

/** A page of the day-edit sheet above its root slot list (issue #122). */
enum class DayEditPage { SWAP, SUPERSET_PATTERN, SUPERSET_EXERCISE, ADD_PATTERN, ADD_EXERCISE, OPTIONS }

/**
 * Which page a back press pops, given which of the sheet's page states are
 * live — deepest first, the same order `DayEditSheet` picks what to show. Null
 * means the sheet is on its root slot list, where back dismisses the sheet
 * rather than stepping (issue #122).
 *
 * Both the ← chip and the system back button route through this, so they can't
 * drift apart.
 */
fun dayEditBackTarget(
    swapping: Boolean,
    supersetSlot: Boolean,
    supersetPatternPicked: Boolean,
    pickingPattern: Boolean,
    addingFromPattern: Boolean,
    options: Boolean,
): DayEditPage? = when {
    swapping -> DayEditPage.SWAP
    supersetSlot && supersetPatternPicked -> DayEditPage.SUPERSET_EXERCISE
    supersetSlot -> DayEditPage.SUPERSET_PATTERN
    pickingPattern -> DayEditPage.ADD_PATTERN
    addingFromPattern -> DayEditPage.ADD_EXERCISE
    options -> DayEditPage.OPTIONS
    else -> null
}
