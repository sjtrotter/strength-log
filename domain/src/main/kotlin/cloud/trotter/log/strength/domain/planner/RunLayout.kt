package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.Riegel
import cloud.trotter.log.strength.domain.progression.RunCap
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.withoutLastRound

private enum class Token { E, Q, Q2, L, R }
private data class Entry(val token: Token, val strength: Boolean = false)
internal data class RunEntry(val session: RunSession?, val strength: Boolean)

private val rows = listOf(
    listOf(Entry(Token.E, true)),
    listOf(Entry(Token.E, true), Entry(Token.L, true)),
    listOf(Entry(Token.E, true), Entry(Token.Q, true), Entry(Token.L), Entry(Token.R)),
    listOf(Entry(Token.E, true), Entry(Token.Q), Entry(Token.E, true), Entry(Token.L), Entry(Token.R)),
    listOf(Entry(Token.E, true), Entry(Token.Q), Entry(Token.E), Entry(Token.Q2, true), Entry(Token.L), Entry(Token.R)),
    listOf(Entry(Token.E, true), Entry(Token.Q), Entry(Token.E), Entry(Token.Q2), Entry(Token.E, true), Entry(Token.L), Entry(Token.R)),
)

internal fun runEntries(answers: SetupAnswers, phase: RunPhase?, todayEpochDay: Long?): List<RunEntry> {
    val run = answers.run
    val q = when {
        phase == RunPhase.LADDER -> RunSession.LADDER
        run.goal == RunGoal.CARDIO_FITNESS -> RunSession.EASY
        run.goal == RunGoal.FITNESS_TEST -> RunSession.TEST_INTERVALS
        run.goal == RunGoal.MIDDLE_DISTANCE -> if (sharpening(run, todayEpochDay)) RunSession.SHARPENING else RunSession.RACE_PACE
        else -> RunSession.THRESHOLD
    }
    val q2 = if (run.experience == RunExperience.NEW) null else when {
        phase == RunPhase.BASE -> RunSession.VO2MAX
        run.goal == RunGoal.MIDDLE_DISTANCE -> RunSession.THRESHOLD
        run.goal == RunGoal.FITNESS_TEST -> RunSession.TEMPO
        run.goal == RunGoal.CARDIO_FITNESS -> RunSession.EASY
        else -> null
    }
    val days = if (phase == RunPhase.LADDER) minOf(answers.days, 3) else answers.days
    val row = if (days == 5 && q2 != null) {
        listOf(Entry(Token.E, true), Entry(Token.Q), Entry(Token.E, true), Entry(Token.Q2), Entry(Token.L), Entry(Token.R))
    } else rows[days - 1]
    var strength = 0
    return row.map { entry ->
        val session = when {
            entry.token == Token.R -> null
            phase == RunPhase.LADDER -> RunSession.LADDER
            else -> when (entry.token) {
                Token.E -> RunSession.EASY
                Token.Q -> q
                Token.Q2 -> q2 ?: RunSession.EASY
                Token.L -> if (run.goal in listOf(RunGoal.FITNESS_TEST, RunGoal.CARDIO_FITNESS)) RunSession.EASY else RunSession.LONG
                Token.R -> null
            }
        }
        RunEntry(session, entry.strength && strength++ < run.strengthBlocks)
    }
}

internal fun strengthBlock(k: Int, answers: SetupAnswers, mains: List<ExerciseEntry>): List<ProgramExercise> {
    val result = balanceBlock(answers).toMutableList()
    val chosen = mains.map { it.id }.toSet()
    fun add(entry: ExerciseEntry?, role: SlotRole? = null) {
        if (entry != null) result += liftSlot(role ?: accessoryRole(entry.pattern), entry, answers).copy(targetSets = 2)
    }
    fun pick(pattern: MovementPattern) = accessoryCandidates(pattern, answers, result).firstOrNull { it.id !in chosen }
    add(mains.firstOrNull { mainRow(it.pattern) == k } ?: mains.firstOrNull { mainRow(it.pattern) == 1 - k }, SlotRole.SECONDARY)
    add(pick(MovementPattern.SINGLE_LEG))
    val calves = listOf(MovementPattern.CALF_GASTROC, MovementPattern.CALF_SOLEUS)
    add(pick(calves[k]) ?: pick(calves[1 - k]))
    add(nordicPick(answers, result, chosen))
    add(pick(listOf(MovementPattern.CORE_ANTI_EXT, MovementPattern.CORE_ANTI_ROT, MovementPattern.CORE_FLEX)[k]))
    return result
}

/** Nordic, else the first other knee-flexion accessory not on [onDay] and not a chosen main. */
internal fun nordicPick(answers: SetupAnswers, onDay: List<ProgramExercise>, chosen: Set<String>): ExerciseEntry? {
    val knee = accessoryCandidates(MovementPattern.KNEE_FLEXION, answers, onDay).filter { it.id !in chosen }
    return knee.firstOrNull { it.id == "nordic" } ?: knee.firstOrNull()
}

internal fun runDraft(
    answers: SetupAnswers,
    known: KnownLoads,
    todayEpochDay: Long?,
    strengthGoal: LiftGoal,
): PlanDraft {
    val run = answers.run
    val anchor = runAnchor(run, known)
    val phase = startPhase(run, anchor, runWeek(run, known))
    val entries = runEntries(answers, phase, todayEpochDay)
    val lone = entries.none { it.session == RunSession.VO2MAX }
    val level = ladderStart(anchor, run.runningInjury)
    val pace = run.recent?.let { it.seconds.toDouble() / it.metres }
    fun template(session: RunSession, easy: Int = 1_200) = runBlock(session, run, phase, anchor, easy, level)
    val long = entries.firstOrNull { it.session == RunSession.LONG }?.let {
        RunCap.capped(template(RunSession.LONG), anchor, pace)
    }
    val blockAnswers = answers.copy(goal = strengthGoal)
    val mains = resolveMains(blockAnswers)
    val reasons = mutableListOf<PlanReason>()
    val sessions = mutableMapOf<String, RunSession>()
    var strength = 0
    val fitted = entries.mapIndexed { index, entry ->
        val id = ('A' + index).toString()
        val session = entry.session
        if (session == null) return@mapIndexed ProgramDay(id, "Rest", "", emptyList(), kind = ProgramDayKind.REST)
        sessions[id] = session
        val template = template(session, easySeconds(answers.minutes, entry.strength, long))
        var block = if (session == RunSession.LADDER) template else RunCap.capped(template, anchor, pace)
        if (block != template) reasons += PlanReason.RunCapped(id)
        val exercises = if (entry.strength) strengthBlock(strength++, blockAnswers, mains) else emptyList()
        var day = ProgramDay(id, session.title, "", exercises, kind = ProgramDayKind.CARDIO, cardioBlock = block,
            cardioRule = runRule(session, run, phase, level, block, lone))
        if (session != RunSession.LONG) {
            if (session != RunSession.LADDER) {
                while (block.sets.count { it.role == CardioRole.WORK } > 1 &&
                    SessionBudget.seconds(day.copy(exercises = emptyList()), strengthGoal) > answers.minutes * 60) {
                    block = block.withoutLastRound()
                    day = day.copy(cardioBlock = block)
                }
            }
            if (day.exercises.isNotEmpty()) {
                val (fittedDay, dayReasons) = fitDay(day, blockAnswers)
                reasons += dayReasons
                day = fittedDay
            } else {
                val excess = SessionBudget.seconds(day, strengthGoal) - answers.minutes * 60
                if (excess > 0) reasons += PlanReason.OverBudget(id, excess)
            }
        }
        day
    }
    val (days, working) = seedAll(fitted, blockAnswers, known)
    reasons += runReasons(run, phase, todayEpochDay)
    return PlanDraft(Program(days), working, if (answers.experience == LiftExperience.A_YEAR_OR_MORE) 6 else null,
        reasons, runGoal(run), phase, sessions)
}

internal fun runGoal(run: RunAnswers): PlanGoal? {
    val seconds = run.goalSeconds
    val event = run.resolvedEvent
    if (event != null && seconds != null) return PlanGoal.Timed(run.activity, event.metres, seconds, run.dateEpochDay)
    if (run.goal != RunGoal.FITNESS_TEST) return null
    val metres = run.test.metres
    if (metres != null && seconds != null) return PlanGoal.Timed(run.activity, metres, seconds, run.dateEpochDay)
    return if (run.test in listOf(FitnessTest.PACER, FitnessTest.HAMR) && run.testScore != null) {
        PlanGoal.Shuttle(run.test.name, run.testScore, byEpochDay = run.dateEpochDay)
    } else null
}

internal fun runReasons(run: RunAnswers, phase: RunPhase?, todayEpochDay: Long?): List<PlanReason> = buildList {
    if (run.dateEpochDay != null && phase != null &&
        run.dateEpochDay - requireNotNull(todayEpochDay) < 7L * weeksNeeded(run.goal, phase)) add(PlanReason.DateTooClose)
    val event = run.resolvedEvent
    if (event != null && event.metres <= RunEvent.MARATHON.metres && run.goalSeconds != null && run.recent != null) {
        val predicted = Riegel.goalCheckSeconds(run.recent.metres.toDouble(), run.recent.seconds.toDouble(), event.metres.toDouble())
        if (run.goalSeconds < predicted) add(PlanReason.GoalCheck(predicted))
    }
}
