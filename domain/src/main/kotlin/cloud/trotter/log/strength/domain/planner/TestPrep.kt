package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.standards.TestEvent
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestSetType

/** Preference-grade prep: half the typed anchor, on at most three lifting days per rotation (#646). */
object TestPrep {
    const val TEST_WEEKS = 4
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

    fun easier(exerciseId: String): String? = when (exerciseId) {
        "hand_release_pushup", "pushup" -> "knee_pushup"
        "plank" -> "knee_plank"
        "pullup", "neutral_pullup" -> "negative_pullup"
        else -> null
    }

    fun slots(answers: SetupAnswers): List<ProgramExercise> = answers.tests.mapNotNull { answer ->
        val event = requireNotNull(TestEvents.matches(answer.goal.ref))
        val entry = ExerciseLibrary.get(event.exerciseId)
        val movement = if (answer.lastResult == 0) ExerciseLibrary.get(requireNotNull(easier(event.exerciseId))) else entry
        if (!entry.availableWith(answers.equipment, answers.excluded) ||
            !movement.availableWith(answers.equipment, answers.excluded)) null else slot(answer, answers)
    }

    fun reservedSeconds(day: ProgramDay, goal: LiftGoal): Int {
        val prep = day.exercises.filter { it.prescription?.test != null }
        if (prep.isEmpty()) return 0
        val onlyPrep = ProgramDay(day.id, day.title, "", prep)
        return SessionBudget.seconds(onlyPrep, goal) -
            SessionBudget.WARM_UP_SECONDS
    }

    internal fun place(exercises: List<ProgramExercise>, prep: List<ProgramExercise>): List<ProgramExercise> =
        exercises.filter { slot -> prep.none { it.exerciseId == slot.exerciseId } } + prep

    internal fun place(days: List<ProgramDay>, answers: SetupAnswers): List<ProgramDay> {
        val prep = slots(answers)
        var placed = 0
        return days.map { day ->
            if (day.kind == ProgramDayKind.REST || day.exercises.none { it.rule?.role != SlotRole.BALANCE } ||
                placed++ >= DAYS_A_ROTATION) day
            else day.copy(exercises = place(day.exercises, prep))
        }
    }

    internal fun slot(answer: TestAnswer, answers: SetupAnswers): ProgramExercise {
        val ref = answer.goal.ref
        val event = requireNotNull(TestEvents.matches(ref))
        require(ref.windowSeconds == TestEvents.window(event, ref.windowSeconds))
        val entry = ExerciseLibrary.get(if (answer.lastResult == 0) requireNotNull(easier(event.exerciseId)) else event.exerciseId)
        val base = liftSlot(accessoryRole(entry.pattern), entry, answers)
        val rule = requireNotNull(base.rule)
        val target = target(event, answer)
        val reps = when (target) {
            is PrescribedTarget.RepsInTime -> requireNotNull(target.repAim)
            is PrescribedTarget.Reps -> target.reps
            else -> rule.target.reps
        }
        return base.copy(targetSets = 3, prescription = Prescription(target, eachSide = entry.eachSide, test = ref),
            rule = rule.copy(target = RepTarget.Fixed(reps),
                rir = maxOf(rule.rir, 2), everyExposures = 1, scheme = Scheme.STRENGTH))
    }
}

internal fun PlanDraft.withTestPrep(answers: SetupAnswers): PlanDraft {
    val goal = answers.testGoal ?: return this
    return copy(testGoal = goal, reasons = reasons + PlanReason.TestPrep(goal.events.size))
}

internal fun PlanDraft.withTestWeeks(answers: SetupAnswers, todayEpochDay: Long?): PlanDraft {
    val date = answers.testDateEpochDay ?: return this
    if (answers.tests.isEmpty() || todayEpochDay == null || date - todayEpochDay >= 7L * TestPrep.TEST_WEEKS ||
        PlanReason.DateTooClose in reasons) return this
    return copy(reasons = reasons + PlanReason.DateTooClose)
}
