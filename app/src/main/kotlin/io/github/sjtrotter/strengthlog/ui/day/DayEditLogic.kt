package io.github.sjtrotter.strengthlog.ui.day

import io.github.sjtrotter.strengthlog.domain.library.ExerciseEntry
import io.github.sjtrotter.strengthlog.domain.model.Equipment

/**
 * Pure search/filter over an already-ranked candidate list (spec §8.3, PLAN.md
 * A4). Candidates must arrive pre-ranked from [io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog]'s
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
