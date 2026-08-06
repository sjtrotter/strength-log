package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.StrengthStandards

/**
 * Pure set-list edits for a single track (spec §4). The cascade rule is the
 * load-bearing one: a TOP-weight edit re-derives the RAMP and BACKOFF rows.
 */
object SetEditor {

    /**
     * Edit one row's weight. Editing a main lift's TOP row re-derives every
     * RAMP row (from [StrengthStandards.RAMP_PCTS], in order) and the BACKOFF
     * row (× [StrengthStandards.BACKOFF]) off the new top weight, rounded to 5.
     * Reps, WORK/EXTRA rows, and every non-TOP edit are left untouched.
     */
    fun editWeight(sets: List<LoggedSet>, index: Int, newWeightLb: Double): List<LoggedSet> {
        if (sets[index].kind != SetKind.TOP) {
            return sets.mapIndexed { i, s -> if (i == index) s.copy(weightLb = newWeightLb) else s }
        }
        var rampSeen = 0
        return sets.map { s ->
            when (s.kind) {
                SetKind.RAMP -> {
                    val pct = StrengthStandards.RAMP_PCTS[rampSeen++]
                    s.copy(weightLb = GoalCalculator.round5(newWeightLb * pct))
                }
                SetKind.TOP -> s.copy(weightLb = newWeightLb)
                SetKind.BACKOFF -> s.copy(weightLb = GoalCalculator.round5(newWeightLb * StrengthStandards.BACKOFF))
                SetKind.WORK, SetKind.EXTRA -> s
            }
        }
    }

    /** Reps never cascade — a reps edit changes only its own row. */
    fun editReps(sets: List<LoggedSet>, index: Int, newReps: Int): List<LoggedSet> =
        sets.mapIndexed { i, s -> if (i == index) s.copy(reps = newReps) else s }

    /** Seconds never cascade — a TIMED edit changes only its own row. */
    fun editSeconds(sets: List<LoggedSet>, index: Int, newSeconds: Int): List<LoggedSet> =
        sets.mapIndexed { i, s -> if (i == index) s.copy(seconds = newSeconds) else s }

    /** "+ add set" appends a copy of the last row as an EXTRA. */
    fun addSet(sets: List<LoggedSet>): List<LoggedSet> {
        val last = sets.last()
        return sets + last.copy(kind = SetKind.EXTRA, done = false)
    }

    /** Remove a row; never drop below one set. */
    fun removeSet(sets: List<LoggedSet>, index: Int): List<LoggedSet> =
        if (sets.size <= 1) sets else sets.filterIndexed { i, _ -> i != index }

    /**
     * Put a row back at [index] — the exact inverse of [removeSet], so an undo
     * reinstates the row it took, kind and done-flag included, rather than a
     * fresh EXTRA copied off the tail. [index] is clamped: the track it came
     * from may have shrunk again before the undo lands.
     */
    fun insertSet(sets: List<LoggedSet>, index: Int, set: LoggedSet): List<LoggedSet> {
        val at = index.coerceIn(0, sets.size)
        return sets.subList(0, at) + set + sets.subList(at, sets.size)
    }

    /**
     * Superset add: append an EXTRA row to both tracks so rounds stay aligned
     * index-for-index (spec §4).
     */
    fun addSetPaired(primary: List<LoggedSet>, partner: List<LoggedSet>): Pair<List<LoggedSet>, List<LoggedSet>> =
        addSet(primary) to addSet(partner)

    /** Superset remove: drop the same index from both tracks, never below one. */
    fun removeSetPaired(
        primary: List<LoggedSet>,
        partner: List<LoggedSet>,
        index: Int,
    ): Pair<List<LoggedSet>, List<LoggedSet>> =
        if (primary.size <= 1) primary to partner
        else removeSet(primary, index) to removeSet(partner, index)

    /** Superset undo: put both tracks' rows back at [index], still aligned. */
    fun insertSetPaired(
        primary: List<LoggedSet>,
        partner: List<LoggedSet>,
        index: Int,
        primarySet: LoggedSet,
        partnerSet: LoggedSet,
    ): Pair<List<LoggedSet>, List<LoggedSet>> =
        insertSet(primary, index, primarySet) to insertSet(partner, index, partnerSet)
}
