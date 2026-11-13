package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole

/** Design §3.1 invariant 5 as the owner set it: weekly set floors and a cap of 20 on rotation totals (§3.1.1's band
 *  tops are not enforced). Trims every strength day; fills only days the ladder left alone. */
internal object VolumeBalance {
    const val CAP = 20.0
    const val SLOT_CAP = 5

    private val patterns = mapOf(
        Muscle.CHEST to listOf(MovementPattern.H_PUSH),
        Muscle.FRONT_DELT to listOf(MovementPattern.V_PUSH),
        Muscle.LATS to listOf(MovementPattern.V_PULL, MovementPattern.H_PULL),
        Muscle.UPPER_BACK to listOf(MovementPattern.H_PULL),
        Muscle.BICEPS to listOf(MovementPattern.BICEPS),
        Muscle.TRICEPS to listOf(MovementPattern.TRICEPS),
        Muscle.QUADS to listOf(MovementPattern.KNEE_EXTENSION, MovementPattern.SINGLE_LEG, MovementPattern.SQUAT_BILATERAL),
        Muscle.HAMSTRINGS to listOf(MovementPattern.KNEE_FLEXION, MovementPattern.HINGE),
        Muscle.GLUTES to listOf(MovementPattern.HINGE, MovementPattern.SINGLE_LEG),
    )

    fun floors(answers: SetupAnswers): Map<Muscle, Int> {
        val sets = when {
            answers.olderAdult -> 6
            answers.goal == LiftGoal.STRENGTH -> 6
            answers.goal == LiftGoal.GENERAL -> 4
            answers.experience == LiftExperience.A_YEAR_OR_MORE -> 12
            else -> 10
        }
        val available = ExerciseLibrary.entries.filter { it.availableWith(answers.equipment, answers.excluded) }
            .flatMap { it.primary }.toSet()
        return patterns.keys.sortedBy { it.ordinal }.filter { muscle ->
            muscle in available && (answers.goal == LiftGoal.MUSCLE || muscle !in setOf(Muscle.BICEPS, Muscle.TRICEPS))
        }.associateWith { sets }
    }

    fun balance(
        days: List<ProgramDay>,
        answers: SetupAnswers,
        chosen: Set<String>,
        touched: Set<String>,
    ): Pair<List<ProgramDay>, List<PlanReason>> {
        val current = days.toMutableList()
        var weekly = SetCount.weekly(Program(current)).toMutableMap()
        while (weekly.values.any { it > CAP }) {
            val over = weekly.filterValues { it > CAP }.keys
            val candidate = current.indices.reversed().flatMap { day ->
                current[day].exercises.indices.reversed().map { slot -> day to slot }
            }.filter { (day, index) ->
                val slot = current[day].exercises[index]
                current[day].kind == ProgramDayKind.STRENGTH && slot.structure == SetStructure.STRAIGHT &&
                    slot.rule?.role in setOf(SlotRole.ACCESSORY, SlotRole.ISOLATION, SlotRole.CORE) &&
                    primary(slot).any(over::contains)
            }.maxByOrNull { (day, index) -> current[day].exercises[index].targetSets } ?: break
            val (day, index) = candidate
            val slots = current[day].exercises.toMutableList()
            val slot = slots[index]
            if (slot.targetSets == 1) slots.removeAt(index) else slots[index] = slot.copy(targetSets = slot.targetSets - 1)
            current[day] = current[day].copy(exercises = slots)
            weekly = SetCount.weekly(Program(current)).toMutableMap()
        }

        val eligible = current.indices.filter { current[it].kind == ProgramDayKind.STRENGTH && current[it].id !in touched }
        val stopped = mutableSetOf<String>()
        for ((muscle, floor) in floors(answers)) {
            while (weekly.getOrDefault(muscle, 0.0) < floor) {
                val minutesBlocked = mutableSetOf<String>()
                fun accept(dayIndex: Int, day: ProgramDay, slot: ProgramExercise): Boolean {
                    val credit = SetCount.weekly(Program(listOf(day.copy(exercises = listOf(slot.copy(targetSets = 1))))))
                    if (credit.any { (credited, sets) -> weekly.getOrDefault(credited, 0.0) + sets > CAP }) return false
                    if (SessionBudget.seconds(day, answers.goal) > answers.minutes * 60) {
                        minutesBlocked += day.id
                        return false
                    }
                    current[dayIndex] = day
                    for ((credited, sets) in credit) weekly[credited] = weekly.getOrDefault(credited, 0.0) + sets
                    return true
                }

                val grow = eligible.flatMap { day -> current[day].exercises.indices.map { day to it } }
                    .filter { (day, index) ->
                        val slot = current[day].exercises[index]
                        slot.structure == SetStructure.STRAIGHT && slot.targetSets < SLOT_CAP &&
                            slot.rule?.role in setOf(SlotRole.MAIN, SlotRole.SECONDARY, SlotRole.ACCESSORY, SlotRole.ISOLATION) &&
                            muscle in primary(slot)
                    }.sortedBy { (day, index) -> current[day].exercises[index].targetSets }
                val grew = grow.any { (dayIndex, index) ->
                    val day = current[dayIndex]
                    val slot = day.exercises[index]
                    val slots = day.exercises.toMutableList()
                    slots[index] = slot.copy(targetSets = slot.targetSets + 1)
                    accept(dayIndex, day.copy(exercises = slots), slot)
                }
                if (grew) continue

                val hosts = eligible.sortedBy { SessionBudget.seconds(current[it], answers.goal) }
                fun addSlot(): Boolean {
                    for (reuseChosen in listOf(false, true)) for (pattern in patterns.getValue(muscle)) for (dayIndex in hosts) {
                        val day = current[dayIndex]
                        val entry = accessoryCandidates(pattern, answers, day.exercises).firstOrNull {
                            muscle in it.primary && (reuseChosen || it.id !in chosen)
                        } ?: continue
                        val slot = liftSlot(accessoryRole(pattern), entry, answers).copy(targetSets = 1)
                        val slots = day.exercises.toMutableList()
                        val core = slots.indexOfFirst { it.rule?.role == SlotRole.CORE }.takeIf { it >= 0 } ?: slots.size
                        slots.add(core, slot)
                        if (accept(dayIndex, day.copy(exercises = slots), slot)) return true
                    }
                    return false
                }
                if (addSlot()) continue
                stopped += minutesBlocked
                break
            }
        }
        return current to current.filter { it.id in stopped }.map { PlanReason.ShortSession(it.id, 0) }
    }

    private fun primary(slot: ProgramExercise): Set<Muscle> =
        listOfNotNull(slot.exerciseId, slot.superset?.exerciseId).flatMap { ExerciseLibrary.get(it).primary }.toSet()
}
