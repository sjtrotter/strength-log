package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.minExercisesPerDay
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.ExerciseSearch
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.model.Equipment

/**
 * Pure search/filter over an already-ranked candidate list (spec §8.3, PLAN.md
 * A4). Candidates arrive in catalog or substitution order; filtering preserves
 * that order so ranked replacements keep the best replacement first.
 */
object ExercisePicker {

    /**
     * Every [query] word matches a name word or its catalog expansion, in any
     * order (blank query matches everything). [equipment] uses [availableWith].
     */
    fun filter(
        candidates: List<ExerciseEntry>,
        query: String,
        equipment: Set<Equipment>,
    ): List<ExerciseEntry> {
        val search = ExerciseSearch(query)
        return candidates.filter { entry -> search.matches(entry.name) && entry.availableWith(equipment) }
    }
}

object DayEditRules {
    fun canRemove(slotCount: Int, hasCardio: Boolean = false): Boolean =
        slotCount > minExercisesPerDay(hasCardio)
}

/** A page of the day-edit sheet above its root slot list. */
enum class DayEditPage { CARDIO, CARDIO_SET, PRESCRIPTION, SWAP, SUPERSET_PATTERN, SUPERSET_EXERCISE, ADD_PATTERN, ADD_EXERCISE, OPTIONS, CARDIO_OPTIONS }

/**
 * Which page a back press pops, given which of the sheet's page states are
 * live — deepest first, the same order `DayEditSheet` picks what to show. Null
 * means the sheet is on its root slot list, where back dismisses the sheet
 * rather than stepping.
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
    prescription: Boolean = false,
    cardio: Boolean = false,
    cardioSet: Boolean = false,
    cardioOptions: Boolean = false,
): DayEditPage? = when {
    cardioSet -> DayEditPage.CARDIO_SET
    cardio -> DayEditPage.CARDIO
    prescription -> DayEditPage.PRESCRIPTION
    swapping -> DayEditPage.SWAP
    supersetSlot && supersetPatternPicked -> DayEditPage.SUPERSET_EXERCISE
    supersetSlot -> DayEditPage.SUPERSET_PATTERN
    pickingPattern -> DayEditPage.ADD_PATTERN
    addingFromPattern -> DayEditPage.ADD_EXERCISE
    cardioOptions -> DayEditPage.CARDIO_OPTIONS
    options -> DayEditPage.OPTIONS
    else -> null
}
