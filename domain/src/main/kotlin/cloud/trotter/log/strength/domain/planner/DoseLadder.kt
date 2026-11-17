package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.standards.RestPolicy

// With no back-offs the back-off fields are never read, but the stored rule codec requires valid values.
private val shortScheme = Scheme.STRENGTH.copy(ramps = listOf(Scheme.Ramp(0.80, 3)), backoffs = 0)

internal fun fitDay(
    day: ProgramDay,
    answers: SetupAnswers,
    budgetSeconds: Int = answers.minutes * 60,
    keepPlyos: Boolean = false,
    keepNordic: Boolean = false,
): Pair<ProgramDay, List<PlanReason>> {
    if (SessionBudget.seconds(day, answers.goal) <= budgetSeconds) return day to emptyList()
    var current = day
    val last = if (keepPlyos || keepNordic) 8 else 6
    for (step in 1..last) {
        val slots = when (step) {
            1 -> {
                var keptCore = false
                current.exercises.mapNotNull { slot ->
                    val role = requireNotNull(slot.rule).role
                    when {
                        keepPlyos && role == SlotRole.PLYO -> slot
                        keepNordic && ExerciseLibrary.get(slot.exerciseId).pattern == MovementPattern.KNEE_FLEXION -> slot
                        else -> when (role) {
                            SlotRole.MAIN, SlotRole.SECONDARY, SlotRole.BALANCE -> slot
                            SlotRole.CORE -> if (keptCore) null else slot.copy(targetSets = 2).also { keptCore = true }
                            else -> null
                        }
                    }
                }
            }
            2 -> current.exercises.filterNot { it.rule?.role == SlotRole.SECONDARY }
            3 -> current.exercises.map { slot ->
                if (slot.rule?.role == SlotRole.MAIN) slot.copy(structure = SetStructure.STRAIGHT, targetSets = 2) else slot
            }
            4 -> current.exercises.map { slot ->
                val rule = requireNotNull(slot.rule)
                if (rule.role != SlotRole.MAIN) slot
                else if (ExerciseLibrary.get(slot.exerciseId).tracking == TrackingType.WEIGHTED) {
                    slot.copy(structure = SetStructure.RAMP_TOP, targetSets = 2, rule = rule.copy(scheme = shortScheme))
                } else slot.copy(structure = SetStructure.STRAIGHT, targetSets = 1)
            }
            5 -> current.exercises.map { slot ->
                val rule = requireNotNull(slot.rule)
                val kind = if (slot.structure.hasTopSet) SetKind.TOP else SetKind.WORK
                val tracking = ExerciseLibrary.get(slot.exerciseId).tracking
                if (rule.role == SlotRole.MAIN &&
                    RestPolicy.effectiveRestSeconds(kind, tracking, emptyMap(), rule, answers.goal) > 120) {
                    slot.copy(rule = rule.copy(restSeconds = 120))
                } else slot
            }
            6 -> current.exercises.filterNot { it.rule?.role == SlotRole.CORE }
            7 -> if (keepPlyos) current.exercises.filterNot { it.rule?.role == SlotRole.PLYO } else current.exercises
            else -> if (keepNordic) current.exercises.filterNot {
                ExerciseLibrary.get(it.exerciseId).pattern == MovementPattern.KNEE_FLEXION
            } else current.exercises
        }
        if (slots.isNotEmpty()) current = current.copy(exercises = slots)
        if (SessionBudget.seconds(current, answers.goal) <= budgetSeconds) {
            return current to listOf(PlanReason.ShortSession(day.id, step))
        }
    }
    return current to listOf(
        PlanReason.ShortSession(day.id, last),
        PlanReason.OverBudget(day.id, SessionBudget.seconds(current, answers.goal) - budgetSeconds),
    )
}
