package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.progression.plannedSeconds
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.RunCap
import cloud.trotter.log.strength.domain.progression.RunSession

internal fun bothDraft(answers: SetupAnswers, known: KnownLoads, todayEpochDay: Long?, atLeast: RunPhase? = null): PlanDraft {
    val run = answers.run
    val anchor = runAnchor(run, known)
    val phase = startPhase(run, anchor, runWeek(run, known), runningSeconds(run, known), runningMetres(run, known), atLeast)
    if (answers.days == 1 && answers.together() == BothAnswers.Together.SEPARATE_DAYS) {
        return if (answers.both.cardioFirst) runDraft(answers, known, todayEpochDay, answers.goal, atLeast)
        else {
            val lift = liftDraft(answers, known)
            lift.copy(goal = runGoal(run), reasons = lift.reasons + runReasons(run, phase, todayEpochDay, null))
        }
    }
    if (answers.together() == BothAnswers.Together.SEPARATE_DAYS) {
        val primaryDays = (answers.days + 1) / 2
        val secondaryDays = answers.days / 2
        val lift = liftDraft(answers.copy(branch = PlanBranch.LIFT,
            days = if (answers.both.cardioFirst) secondaryDays else primaryDays), known)
        val cardio = runDraft(answers.copy(days = if (answers.both.cardioFirst) primaryDays else secondaryDays,
            run = run.copy(strengthBlocks = 0)), known, todayEpochDay, answers.goal, atLeast)
        val sides = if (answers.both.cardioFirst) listOf(cardio, lift) else listOf(lift, cardio)
        val groups = sides.map { draft ->
            val result = mutableListOf<MutableList<ProgramDay>>()
            for (day in draft.program.days) {
                if (day.kind == ProgramDayKind.REST) result.last() += day else result += mutableListOf(day)
            }
            result
        }
        val ids = List(2) { mutableMapOf<String, String>() }
        var position = 0
        for (index in 0 until groups.maxOf { it.size }) for (side in sides.indices) {
            for (day in groups[side].getOrNull(index).orEmpty()) ids[side][day.id] = ('A' + position++).toString()
        }
        val merged = sides.mapIndexed { index, draft -> relabel(draft, ids[index]) }
        return PlanDraft(Program(merged.flatMap { it.program.days }.sortedBy { it.id }),
            merged.flatMap { it.working.entries }.associate { it.toPair() }, lift.deloadEveryCycles,
            merged.flatMap { it.reasons }, cardio.goal, cardio.phase, merged.flatMap { it.sessions.entries }.associate { it.toPair() })
    }
    if (answers.both.cardioFirst) return runDraft(answers, known, todayEpochDay, answers.goal, atLeast)

    val level = ladderStart(runningSeconds(run, known), run.runningInjury)
    val pace = run.recent?.let { it.seconds.toDouble() / it.metres }
    fun template(session: RunSession, easy: Int = 1_200) = runBlock(session, run, phase, anchor, easy, level)
    fun capped(block: CardioBlock) = RunCap.capped(block, anchor, pace)
    val reserve = if (phase == RunPhase.LADDER) plannedSeconds(template(RunSession.LADDER)) else 1_200
    val budget = answers.minutes * 60
    val reservesRun = answers.minutes >= 45
    val lift = liftDraft(answers, known, if (reservesRun) budget - reserve else budget)
    val entries = runEntries(answers, phase, todayEpochDay).mapNotNull { it.session }
    val lone = RunSession.VO2MAX !in entries
    val mapped = if (phase == RunPhase.LADDER) {
        spreadDays(lift.program.days, minOf(lift.program.days.count { it.kind != ProgramDayKind.REST }, 3))
            .associateWith { RunSession.LADDER }
    } else lift.program.days.filter { it.kind != ProgramDayKind.REST }.mapIndexed { index, day ->
        day.id to entries[index % entries.size]
    }.toMap()
    fun fits(day: ProgramDay, block: CardioBlock) = SessionBudget.seconds(day.copy(cardioBlock = block), answers.goal) <= budget
    val long = lift.program.days.firstNotNullOfOrNull { day ->
        if (mapped[day.id] != RunSession.LONG) null
        else capped(template(RunSession.LONG)).takeIf { fits(day, it) }
    }
    fun select(day: ProgramDay, session: RunSession): Pair<RunSession, CardioBlock>? {
        if (session != RunSession.EASY) {
            val original = template(session)
            val block = if (session == RunSession.LADDER) original else capped(original)
            if (fits(day, block) || (reservesRun && session == RunSession.LADDER)) return session to original
            if (!reservesRun) return null
        }
        val remaining = (budget - SessionBudget.seconds(day, answers.goal)) / 60 * 60
        if (!reservesRun && remaining < 1_200) return null
        return RunSession.EASY to template(RunSession.EASY,
            minOf(if (reservesRun) 1_200 else remaining, easyLimit(long) ?: Int.MAX_VALUE))
    }
    val candidates = lift.program.days.mapNotNull { day ->
        val session = mapped[day.id] ?: return@mapNotNull null
        select(day, session)?.let { day.id to it }
    }.toMap()
    val qualityLimit = if (candidates.size <= 3 || run.experience == RunExperience.NEW) 1 else 2
    var quality = 0
    val reasons = lift.reasons.filterNot { it is PlanReason.OverBudget }.toMutableList()
    val sessions = mutableMapOf<String, RunSession>()
    val days = lift.program.days.map { day ->
        var selection = candidates[day.id]
        if (selection?.first?.quality == true && quality++ >= qualityLimit) selection = select(day, RunSession.EASY)
        val session = selection?.first
        val original = selection?.second
        val block = original?.let { if (session == RunSession.LADDER) it else capped(it) }
        if (block != null) {
            sessions[day.id] = requireNotNull(session)
            if (block != original) reasons += PlanReason.RunCapped(day.id)
        }
        val combined = day.copy(cardioBlock = block,
            cardioRule = block?.let { runRule(requireNotNull(session), run, phase, level, it, lone) })
        val excess = SessionBudget.seconds(combined, answers.goal) - budget
        if (excess > 0) reasons += PlanReason.OverBudget(day.id, excess)
        combined
    }
    reasons += runReasons(run, phase, todayEpochDay, ladderWeeks(level, sessions.values.count { it == RunSession.LADDER }))
    return lift.copy(program = Program(days), reasons = reasons, goal = runGoal(run),
        phase = phase.takeIf { sessions.isNotEmpty() }, sessions = sessions)
}

/** Practice: choose the largest smallest cyclic gap, then the fewest gaps at that minimum, then the earliest set
 *  in rotation order. Rest entries occupy positions but cannot be chosen. */
internal fun spreadDays(rotation: List<ProgramDay>, k: Int): List<String> {
    if (k == 0) return emptyList()
    val positions = rotation.indices.filter { rotation[it].kind != ProgramDayKind.REST }
    var best: List<Int> = emptyList()
    var bestGap = -1
    var bestTies = Int.MAX_VALUE
    fun choose(chosen: List<Int>, from: Int) {
        if (chosen.size == k) {
            val gaps = chosen.zipWithNext { a, b -> b - a } + (rotation.size + chosen.first() - chosen.last())
            val gap = gaps.min()
            val ties = gaps.count { it == gap }
            if (gap > bestGap || (gap == bestGap && ties < bestTies)) {
                best = chosen
                bestGap = gap
                bestTies = ties
            }
            return
        }
        for (index in from..positions.size - (k - chosen.size)) choose(chosen + positions[index], index + 1)
    }
    choose(emptyList(), 0)
    return best.map { rotation[it].id }
}

internal fun relabel(draft: PlanDraft, ids: Map<String, String>): PlanDraft = draft.copy(
    program = Program(draft.program.days.map { it.copy(id = ids.getValue(it.id)) }),
    working = draft.working.mapKeys { (key, _) -> key.copy(dayId = ids.getValue(key.dayId)) },
    sessions = draft.sessions.mapKeys { (id, _) -> ids.getValue(id) },
    reasons = draft.reasons.map { reason ->
        when (reason) {
            is PlanReason.ShortSession -> reason.copy(dayId = ids.getValue(reason.dayId))
            is PlanReason.OverBudget -> reason.copy(dayId = ids.getValue(reason.dayId))
            is PlanReason.RunCapped -> reason.copy(dayId = ids.getValue(reason.dayId))
            PlanReason.DateTooClose, is PlanReason.GoalCheck, PlanReason.BalanceDays, PlanReason.BalanceDaysOffered -> reason
        }
    },
)
