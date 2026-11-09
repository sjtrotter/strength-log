package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.units.WeightUnit

object WorkingRows {
    /** Results removed: done, both timestamps, plannedIndex, watchEditedAt. Other values kept. */
    fun cleared(sets: List<LoggedSet>): List<LoggedSet> = sets.map {
        it.copy(done = false, startedAtMillis = null, completedAtMillis = null, plannedIndex = null, watchEditedAt = null)
    }

    /** Next time's rows from a run that opened on [planned]: RAMP and BACKOFF rows keep their logged weights
     *  unless the TOP weight changed; results cleared. */
    fun carried(
        planned: List<LoggedSet>,
        actual: List<LoggedSet>,
    ): List<LoggedSet> {
        fun matched(index: Int) = actual.firstOrNull { it.plannedIndex == index }
        val topIndex = planned.indexOfFirst { it.kind == SetKind.TOP }
        if (topIndex >= 0 && matched(topIndex)?.let { it.weightLb != planned[topIndex].weightLb } == true) {
            return cleared(planned)
        }
        return cleared(planned.mapIndexed { i, row ->
            when (row.kind) {
                SetKind.RAMP, SetKind.BACKOFF -> row.copy(weightLb = matched(i)?.weightLb ?: row.weightLb)
                else -> row
            }
        })
    }

    /** The TOP row of a ramp lift, else every WORK and EXTRA row. */
    fun workingIndices(rows: List<LoggedSet>): List<Int> {
        val top = rows.indexOfFirst { it.kind == SetKind.TOP }
        return if (top >= 0) listOf(top) else rows.indices.filter {
            rows[it].kind == SetKind.WORK || rows[it].kind == SetKind.EXTRA
        }
    }

    /** The first working row's weight, or null without working rows. */
    fun workingWeight(rows: List<LoggedSet>): Double? = workingIndices(rows).firstOrNull()?.let { rows[it].weightLb }

    /** Sets the working weight; a TOP cascades once, while WORK and EXTRA rows take it directly. */
    fun withWorkingWeight(
        rows: List<LoggedSet>,
        weightLb: Double,
        unit: WeightUnit = WeightUnit.LB,
        equipment: Collection<Equipment> = emptyList(),
        scheme: Scheme = Scheme.PROTOTYPE,
    ): List<LoggedSet> {
        val top = rows.indexOfFirst { it.kind == SetKind.TOP }
        return if (top >= 0) SetEditor.editWeight(rows, top, weightLb, unit, equipment, scheme)
        else rows.map {
            if (it.kind == SetKind.WORK || it.kind == SetKind.EXTRA) it.copy(weightLb = weightLb) else it
        }
    }

    /** Every row the lifter is judged on: TOP, BACKOFF, WORK and EXTRA. [workingIndices] is the narrower load anchor. */
    fun workSetIndices(rows: List<LoggedSet>): List<Int> = rows.indices.filter { rows[it].kind != SetKind.RAMP }
}
