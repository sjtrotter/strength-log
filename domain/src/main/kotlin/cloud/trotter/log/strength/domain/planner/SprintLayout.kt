package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.RunRange
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SlotRole

private data class SprintEntry(
    val session: RunSession? = null, val plyos: Boolean = false, val lower: Boolean = false,
    val upper: Boolean = false, val nordic: Boolean = false,
)

/** §3.1.3: "Every sprint day opens with a 10 min build-up block before the first maximal rep". */
private val BUILD_UP = CardioSetSpec(CardioRole.WARMUP, Measure.Time(600))
/** Acceleration without the intro opens at 100 to 200 m of WORK (§3.1.3, revision 30). */
private const val OPENING_METRES = 100
/** 65 contacts, inside 40 to 80 (revision 30). */
private val OPENING_PLYOS = listOf("pogo_hop" to 3, "a_skip" to 1)
/** Nordic 2 × 4 to 6 (§3.1.3). */
private val NORDIC_TARGET = RepTarget.Range(4, 6, 4)
private val SPRINT_ACTIVITY = CardioActivity.TRACK_RUN

private fun sprintEntries(days: Int, event: SprintEvent): List<SprintEntry> {
    val short = event in listOf(SprintEvent.YD40, SprintEvent.M60, SprintEvent.M100)
    val a = if (short) RunSession.ACCELERATION else RunSession.MAX_VELOCITY
    val b = if (short) RunSession.MAX_VELOCITY else RunSession.SPRINT_ENDURANCE
    val rest = SprintEntry()
    return when (days) {
        1 -> listOf(SprintEntry(a, plyos = true, lower = true))
        2 -> listOf(SprintEntry(a, plyos = true, lower = true), rest, SprintEntry(b, upper = true, nordic = true))
        3 -> listOf(SprintEntry(a, plyos = true), rest, SprintEntry(lower = true, nordic = true), SprintEntry(b), rest)
        else -> buildList<SprintEntry> {
            add(SprintEntry(a, plyos = true))
            add(SprintEntry(lower = true))
            add(rest)
            add(SprintEntry(b))
            add(SprintEntry(lower = true, nordic = true))
            if (days >= 5) add(SprintEntry(RunSession.SPRINT_TEMPO))
            if (days >= 6) add(SprintEntry(upper = true))
        }
    }
}

internal fun sprintBlock(session: RunSession, steps: Int): CardioBlock {
    val block = requireNotNull(RunRange.of(session)).block(steps, SPRINT_ACTIVITY)
    return if (session == RunSession.SPRINT_TEMPO) block else block.copy(sets = listOf(BUILD_UP) + block.sets)
}

private fun openingSteps(session: RunSession, intro: Boolean): Int {
    if (intro || session != RunSession.ACCELERATION) return 0
    val range = requireNotNull(RunRange.of(session))
    return (0..range.top).first { steps ->
        range.block(steps, SPRINT_ACTIVITY).sets.filter { it.role == CardioRole.WORK }
            .sumOf { (it.end as Measure.Distance).metres } >= OPENING_METRES
    }
}

private fun lowerLifts(
    k: Int,
    answers: SetupAnswers,
    mains: List<ExerciseEntry>,
    onDay: List<ProgramExercise>,
): List<ProgramExercise> {
    val result = onDay.toMutableList()
    val chosen = mains.map { it.id }.toSet()
    val main = mains.firstOrNull { mainRow(it.pattern) == k } ?: mains.firstOrNull { mainRow(it.pattern) == 1 - k }
    if (main != null) result += liftSlot(SlotRole.MAIN, main, answers)
    for (pattern in listOf(MovementPattern.HINGE, MovementPattern.SINGLE_LEG)) {
        val entry = accessoryCandidates(pattern, answers, result).firstOrNull { it.id !in chosen }
        if (entry != null) result += liftSlot(accessoryRole(pattern), entry, answers)
    }
    return result.drop(onDay.size)
}

private fun upperLifts(answers: SetupAnswers, mains: List<ExerciseEntry>): List<ProgramExercise> =
    mains.filter { mainRow(it.pattern) in 2..4 }.map { liftSlot(SlotRole.MAIN, it, answers) }

/** Design §2.1's Sprint column at creation: two sprint days a rest entry apart, plyometrics on Sprint A,
 *  lift days with [liftGoal]'s rules. Sprint blocks are exempt from the run cap; progression is PR 5c. */
internal fun sprintDraft(answers: SetupAnswers, known: KnownLoads, liftGoal: LiftGoal): PlanDraft {
    val liftAnswers = answers.copy(goal = liftGoal)
    val mains = resolveMains(liftAnswers)
    val chosen = mains.map { it.id }.toSet()
    val reasons = mutableListOf<PlanReason>()
    val sessions = mutableMapOf<String, RunSession>()
    var lowerCount = 0
    val fitted = sprintEntries(answers.days, answers.sprint.event).mapIndexed { index, entry ->
        val id = ('A' + index).toString()
        if (entry == SprintEntry()) {
            return@mapIndexed ProgramDay(id, "Rest", "", emptyList<ProgramExercise>(), kind = ProgramDayKind.REST)
        }
        val exercises = buildList<ProgramExercise> {
            if (entry.lower || entry.upper) addAll(balanceBlock(liftAnswers))
            if (entry.plyos) for ((exerciseId, sets) in OPENING_PLYOS) {
                val exercise = ExerciseLibrary.get(exerciseId)
                if (exercise.availableWith(answers.equipment, answers.excluded)) {
                    add(liftSlot(SlotRole.PLYO, exercise, liftAnswers).copy(targetSets = sets))
                }
            }
            if (entry.lower) addAll(lowerLifts(lowerCount++, liftAnswers, mains, this))
            if (entry.upper) addAll(upperLifts(liftAnswers, mains))
            if (entry.nordic) nordicPick(liftAnswers, this, chosen)?.let { exercise ->
                val slot = liftSlot(accessoryRole(MovementPattern.KNEE_FLEXION), exercise, liftAnswers).copy(targetSets = 2)
                add(if (exercise.id == "nordic") {
                    slot.copy(rule = requireNotNull(slot.rule).copy(target = NORDIC_TARGET))
                } else slot)
            }
        }
        val session = entry.session
        var day = if (session != null) {
            sessions[id] = session
            val steps = openingSteps(session, answers.sprint.intro)
            ProgramDay(id, session.title, "", exercises, kind = ProgramDayKind.CARDIO,
                cardioBlock = sprintBlock(session, steps), cardioRule = CardioRule(null, session, steps = steps))
        } else ProgramDay(id, if (entry.lower) "Lower" else "Upper", "", exercises)
        if (exercises.isNotEmpty()) {
            val (fittedDay, dayReasons) = fitDay(day, liftAnswers)
            reasons += dayReasons
            day = fittedDay
        } else {
            val excess = SessionBudget.seconds(day, liftGoal) - answers.minutes * 60
            if (excess > 0) reasons += PlanReason.OverBudget(id, excess)
        }
        day
    }
    val (days, working) = seedAll(fitted, liftAnswers, known)
    return PlanDraft(Program(days), working, if (answers.experience == LiftExperience.A_YEAR_OR_MORE) 6 else null,
        reasons, PlanGoal.Sprint(answers.sprint.event.metres), phase = null, sessions = sessions)
}
