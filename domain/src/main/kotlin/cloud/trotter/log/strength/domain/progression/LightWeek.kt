package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.math.ceil

/** A light cycle's rows for one run, as a view: RAMP rows unchanged; the first ceil(n × 0.6) of the n work sets
 *  ([WorkingRows.workSetIndices], at least one) kept in order, each load × 0.9 to the nearest step; reps unchanged. */
object LightWeek {
    fun rows(rows: List<LoggedSet>, ctx: Progression.Context): List<LoggedSet> {
        val indices = WorkingRows.workSetIndices(rows)
        val keep = indices.take(maxOf(1, ceil(indices.size * 0.6).toInt())).toSet()
        return rows.mapIndexedNotNull { i, row ->
            when {
                row.kind == SetKind.RAMP -> row
                i !in keep -> null
                row.weightLb == 0.0 -> row
                else -> row.copy(weightLb = ctx.steps.nearest(row.weightLb * 0.9, ctx.equipment, ctx.unit))
            }
        }
    }
}
