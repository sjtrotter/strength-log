package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.standards.TestEvent
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestSetType

/** Preference-grade prep: half the typed anchor, on at most three lifting days per rotation (#646). */
object TestPrep {
    const val DAYS_A_ROTATION = 3
    const val MIN_REPS = 5
    const val MIN_HOLD_SECONDS = 20

    fun aim(event: TestEvent, answer: TestAnswer): Int {
        val anchor = answer.lastResult ?: answer.goal.required
        return if (event.timed) maxOf(MIN_HOLD_SECONDS, anchor / 2 / 5 * 5)
            else maxOf(MIN_REPS, (anchor + 1) / 2)
    }

    fun target(event: TestEvent, answer: TestAnswer): PrescribedTarget = when (event.setType) {
        TestSetType.REPS_IN_WINDOW -> PrescribedTarget.RepsInTime(requireNotNull(answer.goal.ref.windowSeconds), aim(event, answer))
        TestSetType.MAX_HOLD -> PrescribedTarget.Hold(aim(event, answer))
        TestSetType.REPS_CONTINUOUS, TestSetType.REPS_CADENCE -> PrescribedTarget.Reps(aim(event, answer))
    }

    internal fun slot(answer: TestAnswer, answers: SetupAnswers): ProgramExercise {
        val ref = answer.goal.ref
        val event = requireNotNull(TestEvents.matches(ref))
        require(ref.windowSeconds == TestEvents.window(event, ref.windowSeconds))
        val entry = ExerciseLibrary.get(event.exerciseId)
        val base = liftSlot(accessoryRole(entry.pattern), entry, answers)
        val rule = requireNotNull(base.rule)
        val target = target(event, answer)
        return base.copy(targetSets = 3, prescription = Prescription(target, test = ref),
            rule = rule.copy(target = RepTarget.Fixed((target as? PrescribedTarget.RepsInTime)?.repAim ?: rule.target.reps),
                rir = maxOf(rule.rir, 2), everyExposures = 1, scheme = Scheme.STRENGTH))
    }
}

internal fun PlanDraft.withTestPrep(answers: SetupAnswers, known: KnownLoads): PlanDraft {
    val goal = answers.testGoal ?: return this
    val slots = answers.tests.map { TestPrep.slot(it, answers) }
    // Cardio-first Both visits carry lifts on CARDIO days; balance-only days do not count as lifting days.
    val targets = program.days.filter { day ->
        day.kind != ProgramDayKind.REST && day.exercises.any { it.rule?.role != SlotRole.BALANCE }
    }.take(TestPrep.DAYS_A_ROTATION).map { it.id }.toSet()
    var working = this.working
    val days = program.days.map { day ->
        if (day.id !in targets) day else {
            val (seeded, rows) = seedAll(listOf(day.copy(exercises = slots)), answers, known)
            val offset = day.exercises.size
            working = working + rows.map { (key, value) -> key.copy(dayId = day.id, index = key.index + offset) to value }
            day.copy(exercises = day.exercises + seeded.single().exercises)
        }
    }
    return copy(program = Program(days), working = working, testGoal = goal,
        reasons = reasons + PlanReason.TestPrep(goal.events.size))
}
