package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.availableWith
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
import cloud.trotter.log.strength.domain.progression.SprintRules
import cloud.trotter.log.strength.domain.progression.sprintBlock

private enum class LowerDay { ONE_DAY, SQUAT, HINGE }

private data class SprintEntry(
    val session: RunSession? = null,
    val plyos: List<Pair<String, Int>> = emptyList<Pair<String, Int>>(),
    val lower: LowerDay? = null,
    val upperRows: IntRange? = null,
    val nordic: Boolean = false,
)

/** Practice: 40 to 80 contacts a session (revision 30); Sprint B takes 50 (design §2.1, Q7). */
private val FIRST_PLYOS = listOf("pogo_hop" to 3, "a_skip" to 1)
private val SECOND_PLYOS = listOf("pogo_hop" to 2, "a_skip" to 1)
/** Nordic 2 × 4 to 6 (§3.1.3). */
private val NORDIC_TARGET = RepTarget.Range(4, 6, 4)

private fun sprintEntries(days: Int, event: SprintEvent): List<SprintEntry> {
    val short = event in listOf(SprintEvent.YD40, SprintEvent.M60, SprintEvent.M100)
    val a = if (short) RunSession.ACCELERATION else RunSession.MAX_VELOCITY
    val b = if (short) RunSession.MAX_VELOCITY else RunSession.SPRINT_ENDURANCE
    fun high(session: RunSession, plyos: List<Pair<String, Int>>, upperRows: IntRange? = null) =
        SprintEntry(session, plyos, if (session == RunSession.MAX_VELOCITY) LowerDay.HINGE else LowerDay.SQUAT,
            upperRows, nordic = session == RunSession.MAX_VELOCITY)
    val rest = SprintEntry()
    val upper = SprintEntry(upperRows = 2..4)
    return when (days) {
        1 -> listOf(SprintEntry(a, FIRST_PLYOS, LowerDay.ONE_DAY), rest)
        2 -> listOf(high(a, FIRST_PLYOS, 2..2), rest, high(b, SECOND_PLYOS, 3..4), rest)
        3 -> listOf(high(a, FIRST_PLYOS), upper, rest, high(b, SECOND_PLYOS), rest)
        else -> buildList<SprintEntry> {
            add(high(a, FIRST_PLYOS))
            add(upper)
            add(rest)
            add(high(b, SECOND_PLYOS))
            add(upper)
            add(if (days >= 5) SprintEntry(RunSession.SPRINT_TEMPO) else rest)
            if (days >= 6) add(upper)
        }
    }
}

/** Revision 32 opens acceleration at 160 m; S10 keeps non-intro sprint endurance at 3 × 100 m. */
private fun openingSteps(session: RunSession, intro: Boolean): Int {
    if (intro) return 0
    val openingMetres = when (session) {
        RunSession.ACCELERATION -> 160
        RunSession.SPRINT_ENDURANCE -> 300
        RunSession.MAX_VELOCITY, RunSession.SPRINT_TEMPO, RunSession.LADDER, RunSession.EASY,
        RunSession.LONG, RunSession.THRESHOLD, RunSession.TEMPO, RunSession.VO2MAX,
        RunSession.RACE_PACE, RunSession.SHARPENING, RunSession.TEST_INTERVALS -> return 0
    }
    val range = requireNotNull(RunRange.of(session))
    return (0..range.top).first { SprintRules.workMetres(sprintBlock(session, it)) >= openingMetres }
}

private fun lowerLifts(
    day: LowerDay,
    answers: SetupAnswers,
    mains: List<ExerciseEntry>,
    onDay: List<ProgramExercise>,
    squatFallsBackToHinge: Boolean,
): List<ProgramExercise> {
    val result = onDay.toMutableList()
    val chosen = mains.map { it.id }.toSet()
    val squat = mains.firstOrNull { mainRow(it.pattern) == 0 }
    val hinge = mains.firstOrNull { mainRow(it.pattern) == 1 }
    val main = when (day) {
        LowerDay.HINGE -> hinge ?: squat
        LowerDay.SQUAT, LowerDay.ONE_DAY -> squat ?: hinge?.takeIf { squatFallsBackToHinge }
    }
    if (main != null) result += liftSlot(SlotRole.MAIN, main, answers)
    val accessories = when (day) {
        LowerDay.HINGE -> emptyList<MovementPattern>()
        LowerDay.SQUAT -> listOf(MovementPattern.SINGLE_LEG)
        LowerDay.ONE_DAY -> if (main?.pattern == MovementPattern.HINGE) listOf(MovementPattern.SINGLE_LEG)
            else listOf(MovementPattern.HINGE, MovementPattern.SINGLE_LEG)
    }
    for (pattern in accessories) {
        val entry = accessoryCandidates(pattern, answers, result).firstOrNull { it.id !in chosen }
        if (entry != null) result += liftSlot(accessoryRole(pattern), entry, answers)
    }
    return result.drop(onDay.size)
}

private fun upperLifts(rows: IntRange, answers: SetupAnswers, mains: List<ExerciseEntry>): List<ProgramExercise> =
    mains.filter { mainRow(it.pattern) in rows }.map { liftSlot(SlotRole.MAIN, it, answers) }

/** Practice: high/low rotations of design §2.2, with plyometrics on both high days and hinge + Nordic on the
 *  max-velocity day. The one-day practice dose has a squat, hinge accessory and single-leg accessory.
 *  Both needs a resolved lower main (a squat for the careful group) to keep two lifting days from two days up.
 *  Lifts use [liftGoal]'s rules; sprint blocks are exempt from the run cap and [SprintRules] steps them. */
internal fun sprintDraft(answers: SetupAnswers, known: KnownLoads, liftGoal: LiftGoal): PlanDraft {
    val liftAnswers = answers.copy(goal = liftGoal)
    val mains = resolveMains(liftAnswers)
    if (answers.branch == PlanBranch.BOTH) {
        val rows = mains.map { mainRow(it.pattern) }
        if (answers.careful) require(0 in rows) { "Both with a sprint goal needs a squat main" }
        else require(0 in rows || 1 in rows) { "Both with a sprint goal needs a squat or hinge main" }
    }
    val chosen = mains.map { it.id }.toSet()
    val reasons = mutableListOf<PlanReason>()
    val sessions = mutableMapOf<String, RunSession>()
    val fitted = sprintEntries(answers.days, answers.sprint.event).mapIndexed { index, entry ->
        val id = ('A' + index).toString()
        val upper = entry.upperRows?.let { upperLifts(it, liftAnswers, mains) } ?: emptyList<ProgramExercise>()
        if (entry == SprintEntry() || (entry.session == null && entry.upperRows != null && upper.isEmpty())) {
            return@mapIndexed ProgramDay(id, "Rest", "", emptyList<ProgramExercise>(), kind = ProgramDayKind.REST)
        }
        val exercises = buildList<ProgramExercise> {
            if (entry.lower != null || upper.isNotEmpty()) addAll(balanceBlock(liftAnswers))
            for ((exerciseId, sets) in entry.plyos) {
                val exercise = ExerciseLibrary.get(exerciseId)
                if (exercise.availableWith(answers.equipment, answers.excluded)) {
                    add(liftSlot(SlotRole.PLYO, exercise, liftAnswers).copy(targetSets = sets))
                }
            }
            entry.lower?.let { lower ->
                addAll(lowerLifts(lower, liftAnswers, mains, this,
                    squatFallsBackToHinge = answers.branch == PlanBranch.BOTH && !answers.careful))
            }
            addAll(upper)
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
            val steps = openingSteps(session, answers.sprintIntro)
            ProgramDay(id, session.title, "", exercises, kind = ProgramDayKind.CARDIO,
                cardioBlock = sprintBlock(session, steps), cardioRule = CardioRule(null, session, steps = steps,
                    intro = if (answers.sprintIntro) {
                        if (answers.careful) SprintRules.CAREFUL_INTRO_SESSIONS else SprintRules.INTRO_SESSIONS
                    } else 0))
        } else ProgramDay(id, "Upper", "", exercises)
        if (exercises.isNotEmpty()) {
            val (fittedDay, dayReasons) = fitDay(day, liftAnswers, keepPlyos = answers.branch == PlanBranch.SPRINT, keepNordic = true)
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
