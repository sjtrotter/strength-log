package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.ProgramExercise

/** Slots on different days of one plan share progression (#629); callers enforce day and MAIN track scope. */
object SharedProgression {
    fun shares(a: ProgramExercise, b: ProgramExercise, planAutomatic: Boolean): Boolean {
        val first = a.rule ?: return false
        val second = b.rule ?: return false
        return !a.keepToPlan && !b.keepToPlan && a.exerciseId == b.exerciseId &&
            a.structure == b.structure && a.targetSets == b.targetSets &&
            first.role == second.role && first.rir == second.rir && first.scheme == second.scheme &&
            first.automatic(planAutomatic) == second.automatic(planAutomatic) &&
            dose(a, first) == dose(b, second)
    }

    fun copied(into: SlotRule, from: SlotRule): SlotRule =
        into.copy(target = from.target, everyExposures = from.everyExposures, state = from.state)

    private data class Dose(val authored: PrescribedTarget?, val eachSide: Boolean, val ruled: RepTarget?)

    private fun dose(slot: ProgramExercise, rule: SlotRule): Dose {
        val prescription = slot.prescription ?: return Dose(null, false, when (val target = rule.target) {
            is RepTarget.Fixed -> target
            is RepTarget.Range -> target.copy(current = target.lo)
        })
        val authored = when (val target = prescription.target) {
            is PrescribedTarget.LoadReps -> target.copy(lb = 0.0)
            is PrescribedTarget.Hold -> target.copy(addedLb = 0.0)
            is PrescribedTarget.Reps, is PrescribedTarget.RepsInTime -> target
        }
        return Dose(authored, prescription.eachSide, null)
    }
}
