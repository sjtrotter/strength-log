package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.GeneratedLoad
import cloud.trotter.log.strength.domain.units.PlateMath
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit

/** Working values retained after a run closes. */
object CarryForward {
    data class Session(val planned: List<LoggedSet>, val actual: List<LoggedSet>)

    /** Results removed: done, both timestamps, plannedIndex, watchEditedAt. Other values kept. */
    fun cleared(sets: List<LoggedSet>): List<LoggedSet> = sets.map {
        it.copy(done = false, startedAtMillis = null, completedAtMillis = null, plannedIndex = null, watchEditedAt = null)
    }

    /** Next time's working values from a finished run's [planned] and [actual] rows. */
    @Suppress("UNUSED_PARAMETER") // Retain the unit-aware API; Finish no longer generates loads.
    fun next(
        planned: List<LoggedSet>,
        actual: List<LoggedSet>,
        unit: WeightUnit = WeightUnit.LB,
        equipment: Collection<Equipment> = emptyList(),
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

    /** The increase to offer after a run, in lb; null when there is none. */
    fun offer(
        planned: List<LoggedSet>,
        actual: List<LoggedSet>,
        unit: WeightUnit,
        equipment: Collection<Equipment> = emptyList(),
    ): Double? = offer(planned, unit, listOf(Session(planned, actual)), equipment)

    /** Uses the latest qualifying session; [sessions] must be newest first. */
    fun offer(
        planned: List<LoggedSet>,
        unit: WeightUnit,
        sessions: List<Session>,
        equipment: Collection<Equipment> = emptyList(),
    ): Double? {
        val working = workingIndices(planned)
        val opening = workingWeight(planned) ?: return null
        if (opening <= 0.0 || planned[working.first()].seconds > 0) return null
        return sessions.firstNotNullOfOrNull { session -> offerForSession(opening, session, unit, equipment) }
    }

    private fun offerForSession(
        opening: Double,
        session: Session,
        unit: WeightUnit,
        equipment: Collection<Equipment>,
    ): Double? {
        val (planned, actual) = session
        val working = workingIndices(planned)
        if (working.isEmpty() || planned[working.first()].seconds > 0) return null
        fun matched(index: Int) = actual.firstOrNull { it.plannedIndex == index && it.done }
        val sameOpening = workingWeight(planned) == opening
        val fullSuccess = working.all { i ->
            matched(i)?.let {
                it.weightLb >= planned[i].weightLb && (sameOpening || it.weightLb >= opening) && it.reps >= planned[i].reps
            } == true
        }
        val stepOffer = if (fullSuccess) {
            val step = unit.toLb(WeightStepper.increment(unit.fromLb(opening), unit))
            val load = GeneratedLoad.snap(opening + step, unit)
            if (Equipment.BARBELL in equipment) maxOf(load, unit.toLb(PlateMath.barWeight(unit))) else load
        } else null
        val heavier = working.mapNotNull { i ->
            matched(i)?.takeIf { it.weightLb > opening && it.reps >= planned[i].reps }?.weightLb
        }.maxOrNull()
        return listOfNotNull(stepOffer, heavier).maxOrNull()
    }

    /** Sets the working weight; a TOP cascades once, while WORK and EXTRA rows take it directly. */
    fun withWorkingWeight(
        rows: List<LoggedSet>,
        weightLb: Double,
        unit: WeightUnit = WeightUnit.LB,
        equipment: Collection<Equipment> = emptyList(),
    ): List<LoggedSet> {
        val top = rows.indexOfFirst { it.kind == SetKind.TOP }
        return if (top >= 0) SetEditor.editWeight(rows, top, weightLb, unit, equipment)
        else rows.map {
            if (it.kind == SetKind.WORK || it.kind == SetKind.EXTRA) it.copy(weightLb = weightLb) else it
        }
    }
}
