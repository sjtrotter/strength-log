package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.units.WeightUnit

/** A link resolves to the day's first linkable slot of the exercise; ratio is follower load over source load. */
data class LoadSource(val dayId: String, val ratio: Double) {
    init { require(ratio.isFinite() && ratio > 0.0) }
}

object LoadFollowing {
    fun validRatio(ratio: Double): Boolean = ratio.isFinite() && ratio > 0.0 && ratio <= 4.0

    fun ratio(rule: SlotRule, sourceRule: SlotRule, loadLb: Double?, sourceLoadLb: Double?): Double? =
        (if (loadLb != null && sourceLoadLb != null) loadLb / sourceLoadLb
        else Estimate.loadRatio(rule.target.reps, rule.rir, sourceRule.target.reps, sourceRule.rir))?.takeIf(::validRatio)

    fun linkable(slot: ProgramExercise, tracking: TrackingType): Boolean {
        val authored = slot.prescription?.target
        return slot.rule != null && !slot.keepToPlan && tracking == TrackingType.WEIGHTED &&
            (authored == null || authored is PrescribedTarget.LoadReps)
    }

    /** Estimate bounds new links only; an established range may climb past the bound. */
    fun withinRange(rule: SlotRule): Boolean = rule.target.reps + rule.rir <= Estimate.MAX_REPS

    fun eligible(slot: ProgramExercise, tracking: TrackingType): Boolean =
        linkable(slot, tracking) && withinRange(requireNotNull(slot.rule))

    fun held(rule: SlotRule): Boolean = rule.state.repMissStreak > 0 || rule.state.effortMissStreak > 0

    fun source(day: ProgramDay, exerciseId: String, tracking: TrackingType): ProgramExercise? =
        day.exercises.firstOrNull { it.exerciseId == exerciseId && linkable(it, tracking) }

    /** A range restarts only on a load rise, including a move from another day's Finish. */
    fun atLoad(rows: List<LoggedSet>, rule: SlotRule, loadLb: Double, unit: WeightUnit,
               equipment: Collection<Equipment>): Pair<List<LoggedSet>, SlotRule> {
        val prior = WorkingRows.workingWeight(rows)
        val loaded = WorkingRows.withWorkingWeight(rows, loadLb, unit, equipment, rule.scheme)
        val target = rule.target
        if (target !is RepTarget.Range || prior == null || loadLb <= prior) return loaded to rule
        return WorkingRows.withReps(loaded, target.lo) to rule.copy(target = target.copy(current = target.lo))
    }

    fun eligibleSources(program: Program, dayId: String, slot: ProgramExercise, tracking: TrackingType): List<String> {
        if (!eligible(slot, tracking)) return emptyList()
        return liveSources(program, dayId, slot, tracking).filter { id ->
            withinRange(requireNotNull(requireNotNull(sourceOn(program, id, slot.exerciseId, tracking)).rule))
        }
    }

    /** Reject a chain that returns to the follower's day, even through another follower. */
    fun liveSources(program: Program, dayId: String, slot: ProgramExercise, tracking: TrackingType): List<String> {
        if (!linkable(slot, tracking)) return emptyList()
        fun next(id: String): String? = sourceOn(program, id, slot.exerciseId, tracking)?.rule?.loadSource?.dayId
        fun reaches(from: String): Boolean {
            val seen = mutableSetOf<String>()
            var at: String? = from
            while (at != null && seen.add(at)) {
                if (at == dayId) return true
                at = next(at)
            }
            return false
        }
        return program.strengthDays.filter {
            it.id != dayId && sourceOn(program, it.id, slot.exerciseId, tracking) != null && !reaches(it.id)
        }.map { it.id }
    }

    fun followedDay(program: Program, dayId: String, slot: ProgramExercise, tracking: TrackingType): String? =
        slot.rule?.loadSource?.dayId?.takeIf { it in liveSources(program, dayId, slot, tracking) }

    private fun sourceOn(program: Program, dayId: String, exerciseId: String, tracking: TrackingType): ProgramExercise? =
        program.strengthDays.firstOrNull { it.id == dayId }?.let { source(it, exerciseId, tracking) }
}
