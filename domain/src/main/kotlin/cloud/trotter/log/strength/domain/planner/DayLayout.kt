package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.MovementPattern.BICEPS
import cloud.trotter.log.strength.domain.model.MovementPattern.CALF_GASTROC
import cloud.trotter.log.strength.domain.model.MovementPattern.CALF_SOLEUS
import cloud.trotter.log.strength.domain.model.MovementPattern.CORE_ANTI_EXT
import cloud.trotter.log.strength.domain.model.MovementPattern.CORE_ANTI_ROT
import cloud.trotter.log.strength.domain.model.MovementPattern.CORE_FLEX
import cloud.trotter.log.strength.domain.model.MovementPattern.HINGE
import cloud.trotter.log.strength.domain.model.MovementPattern.H_PULL
import cloud.trotter.log.strength.domain.model.MovementPattern.H_PUSH
import cloud.trotter.log.strength.domain.model.MovementPattern.KNEE_FLEXION
import cloud.trotter.log.strength.domain.model.MovementPattern.REAR_DELT
import cloud.trotter.log.strength.domain.model.MovementPattern.SIDE_DELT
import cloud.trotter.log.strength.domain.model.MovementPattern.SINGLE_LEG
import cloud.trotter.log.strength.domain.model.MovementPattern.SQUAT_BILATERAL
import cloud.trotter.log.strength.domain.model.MovementPattern.TRICEPS
import cloud.trotter.log.strength.domain.model.MovementPattern.V_PULL
import cloud.trotter.log.strength.domain.model.MovementPattern.V_PUSH
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.units.GeneratedLoad

private enum class Shape(val title: String) {
    FB("Full Body"), UPPER("Upper"), LOWER("Lower"), PUSH("Push"), PULL("Pull"), LEGS("Legs");

    fun hosts(pattern: MovementPattern): Boolean = when (this) {
        FB -> true
        LOWER, LEGS -> mainRow(pattern) in 0..1
        UPPER -> mainRow(pattern) in 2..4
        PUSH -> mainRow(pattern) in 2..3
        PULL -> mainRow(pattern) == 4
    }
}

internal fun layout(answers: SetupAnswers, mains: List<ExerciseEntry>): List<ProgramDay> {
    val shapes = when {
        answers.goal == LiftGoal.GENERAL || answers.days <= 3 -> List(answers.days) { Shape.FB }
        answers.goal == LiftGoal.MUSCLE && answers.days == 6 ->
            listOf(Shape.PUSH, Shape.PULL, Shape.LEGS, Shape.PUSH, Shape.PULL, Shape.LEGS)
        else -> List(answers.days) { i ->
            if (i == 4 && answers.days == 5) Shape.FB else if (i % 2 == 0) Shape.LOWER else Shape.UPPER
        }
    }
    val fullBodyOnly = shapes.all { it == Shape.FB }
    val slots = List(shapes.size) { mutableListOf<ProgramExercise>() }
    val assigned = mutableMapOf<String, Int>()
    val nextByFamily = mutableMapOf<List<Int>, Int>()
    for ((index, entry) in mains.withIndex()) {
        val hosts = shapes.indices.filter { shapes[it].hosts(entry.pattern) &&
            (fullBodyOnly || shapes[it] != Shape.FB) }
        val next = nextByFamily.getOrDefault(hosts, 0)
        val day = if (fullBodyOnly) index % shapes.size else hosts[next % hosts.size]
        nextByFamily[hosts] = next + 1
        assigned[entry.id] = day
        slots[day] += liftSlot(SlotRole.MAIN, entry, answers)
    }
    if (answers.goal != LiftGoal.GENERAL && !answers.olderAdult) {
        for (entry in mains) {
            val origin = assigned.getValue(entry.id)
            val next = (1 until shapes.size).map { (origin + it) % shapes.size }.firstOrNull { i ->
                shapes[i].hosts(entry.pattern) && slots[i].none { it.exerciseId == entry.id }
            }
            if (next != null) slots[next] += liftSlot(SlotRole.SECONDARY, entry, answers)
        }
    }
    val days = shapes.mapIndexed { training, shape ->
        val occurrence = shapes.take(training).count { it == shape }
        ProgramDay("", shape.title, "", dayContents(shape, occurrence, training, slots[training], mains, answers))
    }.toMutableList()
    for ((training, shape) in shapes.withIndex()) {
        val exposures = SetCount.exposures(Program(days))
        val missing = setOf(Muscle.UPPER_BACK, Muscle.LATS).filter {
            exposures.getOrDefault(it, 0) < minOf(2, answers.days)
        }.toSet()
        if (missing.isEmpty()) break
        val onDay = SetCount.exposures(Program(listOf(days[training]))).keys
        val needed = missing - onDay
        if (needed.isEmpty()) continue
        val occurrence = shapes.take(training).count { it == shape }
        days[training] = days[training].copy(exercises =
            dayContents(shape, occurrence, training, slots[training], mains, answers, reusePullFor = needed))
    }
    val rotation = mutableListOf<ProgramDay>()
    for ((training, day) in days.withIndex()) {
        if (day.exercises.isNotEmpty()) {
            rotation += day.copy(id = ('A' + rotation.size).toString())
        }
        if (training == 2 && answers.days >= 4) {
            rotation += ProgramDay(('A' + rotation.size).toString(), "Rest", "", emptyList(), kind = ProgramDayKind.REST)
        }
    }
    return rotation
}

private fun dayContents(
    shape: Shape,
    occurrence: Int,
    training: Int,
    anchors: List<ProgramExercise>,
    mains: List<ExerciseEntry>,
    answers: SetupAnswers,
    reusePullFor: Set<Muscle> = emptySet(),
): List<ProgramExercise> {
    val result = mutableListOf<ProgramExercise>()
    val chosen = mains.map { it.id }.toSet()
    result += balanceBlock(answers)
    result += anchors
    val unfilled = anchors.toMutableList()
    fun pick(pattern: MovementPattern, reusePull: Boolean = false): ExerciseEntry? =
        accessoryCandidates(pattern, answers, result).firstOrNull { entry ->
            entry.id !in chosen || (reusePull && mainRow(entry.pattern) == 4 &&
                (entry.primary + entry.secondary).any(reusePullFor::contains))
        }
    fun fill(patterns: List<MovementPattern>) {
        val anchor = unfilled.firstOrNull { ExerciseLibrary.get(it.exerciseId).pattern in patterns }
        if (anchor != null) {
            unfilled.remove(anchor)
            return
        }
        val entry = patterns.firstNotNullOfOrNull { pick(it) }
            ?: patterns.firstNotNullOfOrNull { pick(it, reusePull = true) }
            ?: return
        result += liftSlot(accessoryRole(entry.pattern), entry, answers)
    }
    fun arms() {
        val biceps = pick(BICEPS)
        val triceps = pick(TRICEPS)
        val entry = biceps ?: triceps ?: return
        val slot = liftSlot(SlotRole.ISOLATION, entry, answers)
        result += if (biceps != null && triceps != null) {
            slot.copy(superset = SupersetPartner(triceps.id, liftSlot(SlotRole.ISOLATION, triceps, answers).rule))
        } else slot
    }
    fun alternating(first: MovementPattern, second: MovementPattern) =
        if (occurrence % 2 == 0) listOf(first, second) else listOf(second, first)
    val calf = if (occurrence % 2 == 0) CALF_GASTROC else CALF_SOLEUS
    when (shape) {
        Shape.FB -> {
            fill(alternating(SQUAT_BILATERAL, SINGLE_LEG))
            fill(alternating(HINGE, KNEE_FLEXION))
            fill(listOf(H_PUSH))
            fill(alternating(H_PULL, V_PULL))
            when (occurrence % 3) {
                0 -> fill(listOf(SIDE_DELT))
                1 -> arms()
                2 -> fill(listOf(calf))
            }
        }
        Shape.UPPER -> {
            for (pattern in listOf(H_PUSH, H_PULL, V_PUSH, V_PULL,
                if (occurrence % 2 == 0) SIDE_DELT else REAR_DELT)) fill(listOf(pattern))
            arms()
        }
        Shape.LOWER, Shape.LEGS -> {
            for (pattern in listOf(SQUAT_BILATERAL, HINGE, SINGLE_LEG, KNEE_FLEXION, calf)) fill(listOf(pattern))
        }
        Shape.PUSH -> {
            for (pattern in listOf(H_PUSH, H_PUSH, V_PUSH, SIDE_DELT, TRICEPS)) fill(listOf(pattern))
        }
        Shape.PULL -> {
            for (pattern in listOf(H_PULL, V_PULL, REAR_DELT, BICEPS)) fill(listOf(pattern))
        }
    }
    fill(listOf(listOf(CORE_ANTI_EXT, CORE_ANTI_ROT, CORE_FLEX)[training % 3]))
    return result
}

/** D8's accessory candidates for [pattern] by subRank: available and not excluded, not main-capable, not already on
 *  [onDay] as a slot or partner, not the loaded side of a weighted pair. Chosen mains are the caller's filter. */
internal fun accessoryCandidates(
    pattern: MovementPattern,
    answers: SetupAnswers,
    onDay: List<ProgramExercise>,
): List<ExerciseEntry> = ExerciseLibrary.byPattern(pattern).filter { entry ->
    entry.availableWith(answers.equipment, answers.excluded) && !entry.mainCapable &&
        onDay.none { it.exerciseId == entry.id || it.superset?.exerciseId == entry.id } &&
        ExerciseLibrary.bodyweightPairFor(entry.id) == null
}

internal fun balanceBlock(answers: SetupAnswers): List<ProgramExercise> {
    val result = mutableListOf<ProgramExercise>()
    if (answers.olderAdult) {
        val stance = ExerciseLibrary.get("single_leg_stance")
        if (stance.availableWith(answers.equipment, answers.excluded)) result += liftSlot(SlotRole.BALANCE, stance, answers)
        val carry = ExerciseLibrary.get("farmers_carry")
        if (carry.availableWith(answers.equipment, answers.excluded)) {
            val load = (carry.goal as GoalSource.Time).addedWeightLb
            result += liftSlot(SlotRole.BALANCE, carry, answers).copy(
                prescription = Prescription(PrescribedTarget.Hold(20, GeneratedLoad.snap(load, answers.unit))),
            )
        }
    }
    return result
}
