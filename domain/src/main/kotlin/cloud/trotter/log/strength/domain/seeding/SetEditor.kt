package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.units.GeneratedLoad
import cloud.trotter.log.strength.domain.units.WeightUnit

/**
 * Pure set-list edits for a single track (spec §4). The cascade rule is the
 * load-bearing one: a TOP-weight edit re-derives the RAMP and BACKOFF rows.
 */
object SetEditor {

    fun edit(
        sets: List<LoggedSet>,
        index: Int,
        edit: SetEdit,
        unit: WeightUnit = WeightUnit.LB,
        equipment: Collection<Equipment> = emptyList(),
        scheme: Scheme = Scheme.PROTOTYPE,
    ): List<LoggedSet> {
        var updated = sets
        edit.weightLb?.let { updated = editWeight(updated, index, it, unit, equipment, scheme) }
        edit.reps?.let { updated = editReps(updated, index, it) }
        edit.seconds?.let { updated = editSeconds(updated, index, it) }
        return updated.mapIndexed { i, row ->
            if (i != index) row else {
                val unticking = edit.done == false
                val filling = edit.done == true && row.done
                row.copy(
                    done = edit.done ?: row.done,
                    rir = if (edit.done != null && edit.done != row.done) null else row.rir,
                    startedAtMillis = when {
                        unticking -> null
                        filling -> row.startedAtMillis ?: edit.startedAtMillis
                        else -> edit.startedAtMillis ?: row.startedAtMillis
                    },
                    completedAtMillis = when {
                        unticking -> null
                        filling -> row.completedAtMillis ?: edit.completedAtMillis
                        else -> edit.completedAtMillis ?: row.completedAtMillis
                    },
                    watchEditedAt = edit.watchEditedAt ?: row.watchEditedAt,
                )
            }
        }
    }

    /** One row's effort; null when the row can't be rated or already holds it. */
    fun effort(sets: List<LoggedSet>, index: Int, effort: Effort?): List<LoggedSet>? {
        val row = sets.getOrNull(index) ?: return null
        if (!row.done || row.kind == SetKind.RAMP || row.seconds > 0 || row.rir == effort?.rir) return null
        return sets.mapIndexed { i, set -> if (i == index) set.copy(rir = effort?.rir) else set }
    }

    /**
     * Edit one row's weight. Editing a TOP row re-derives every
     * RAMP and BACKOFF row using the percentages from [scheme], rounded for
     * the unit and equipment.
     * Reps, WORK/EXTRA rows, and every non-TOP edit are left untouched.
     * Rows already done keep their weight.
     */
    fun editWeight(
        sets: List<LoggedSet>,
        index: Int,
        newWeightLb: Double,
        unit: WeightUnit = WeightUnit.LB,
        equipment: Collection<Equipment> = emptyList(),
        scheme: Scheme = Scheme.PROTOTYPE,
    ): List<LoggedSet> {
        if (sets[index].kind != SetKind.TOP) {
            return sets.mapIndexed { i, s -> if (i == index) s.copy(weightLb = newWeightLb) else s }
        }
        var rampSeen = 0
        return sets.mapIndexed { i, s ->
            when (s.kind) {
                SetKind.RAMP -> {
                    val pct = scheme.ramps.getOrNull(rampSeen++)?.pct
                    if (s.done || pct == null) s else s.copy(weightLb = GeneratedLoad.ramp(newWeightLb * pct, unit, equipment))
                }
                SetKind.TOP -> if (s.done && i != index) s else s.copy(weightLb = newWeightLb)
                SetKind.BACKOFF -> if (s.done) s else s.copy(weightLb = GeneratedLoad.ramp(newWeightLb * scheme.backoffPct, unit, equipment))
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
        return sets + last.copy(kind = SetKind.EXTRA, done = false, plannedIndex = null, watchEditedAt = null)
    }

    /** Keeps each index's newest watch stamp across a remove or insert, so a watch edit replayed
     *  by index after the rows shift is still judged stale. */
    fun keepingIndexStamps(before: List<LoggedSet>, after: List<LoggedSet>): List<LoggedSet> =
        after.mapIndexed { i, row ->
            row.copy(watchEditedAt = listOfNotNull(row.watchEditedAt, before.getOrNull(i)?.watchEditedAt).maxOrNull())
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
}
