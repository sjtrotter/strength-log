package io.github.sjtrotter.strengthlog.domain.seeding

import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.standards.StrengthStandards

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
}
