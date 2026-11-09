package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.math.ceil

/** A light cycle's rows for one run, as a view: RAMP rows unchanged; the first ceil(n × 0.6) of the n work sets
 *  ([WorkingRows.workSetIndices], at least one) kept in order, each load × 0.9 to the nearest step; reps unchanged. */
object LightWeek {
    /** The counters after a finished training day (design §3.2 Light cycle): [LightCycle.remaining] falls by one; a
     *  [wrapped] rotation adds a cycle and fires the planned light cycle every [deloadEveryCycles] cycles; [reactive]
     *  fires one outside a light cycle. A fired cycle lasts [trainingEntries] training days. */
    fun afterDay(cycle: LightCycle, trainingEntries: Int, wrapped: Boolean, deloadEveryCycles: Int?, reactive: Boolean): LightCycle {
        val cycles = cycle.cyclesCompleted + if (wrapped) 1 else 0
        val planned = wrapped && deloadEveryCycles != null && deloadEveryCycles > 0 && cycles % deloadEveryCycles == 0
        val fired = planned || (reactive && cycle.remaining == 0)
        return LightCycle(if (fired) maxOf(1, trainingEntries) else maxOf(0, cycle.remaining - 1), cycles)
    }

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

/** A plan's light-cycle counters (`plan.lightCycleRemaining`, `plan.cyclesCompleted`). */
data class LightCycle(val remaining: Int, val cyclesCompleted: Int)
