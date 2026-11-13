package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.RunCap

internal fun bothDraft(answers: SetupAnswers, known: KnownLoads, todayEpochDay: Long?): PlanDraft {
    val run = answers.run
    val anchor = known.longestRecent[CardioFamily.of(run.activity)] ?: run.seed()
    val phase = startPhase(run, anchor)
    if (answers.days == 1) {
        return if (answers.both.cardioFirst) runDraft(answers, known, todayEpochDay, answers.goal)
        else {
            val lift = liftDraft(answers, known)
            lift.copy(goal = runGoal(run), reasons = lift.reasons + runReasons(run, phase, todayEpochDay))
        }
    }
    if (answers.both.together(answers.days) == BothAnswers.Together.SEPARATE_DAYS) {
        val primaryDays = (answers.days + 1) / 2
        val secondaryDays = answers.days / 2
        val lift = liftDraft(answers.copy(branch = PlanBranch.LIFT,
            days = if (answers.both.cardioFirst) secondaryDays else primaryDays), known)
        val cardio = runDraft(answers.copy(days = if (answers.both.cardioFirst) primaryDays else secondaryDays,
            run = run.copy(strengthBlocks = 0)), known, todayEpochDay, answers.goal)
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
    if (answers.both.cardioFirst) return runDraft(answers, known, todayEpochDay, answers.goal)

    val level = ladderStart(anchor, run.runningInjury)
    val pace = run.recent?.let { it.seconds.toDouble() / it.metres }
    fun template(session: RunSession, easy: Int = 1_200) = runBlock(session, run, phase, anchor, easy, level, todayEpochDay)
    fun capped(block: CardioBlock) = RunCap.capped(block, anchor, pace)
    val reserve = if (phase == RunPhase.LADDER) plannedSeconds(template(RunSession.LADDER)) else 1_200
    val budget = answers.minutes * 60
    val reservesRun = answers.minutes >= 45
    val lift = liftDraft(answers, known, if (reservesRun) budget - reserve else budget)
    val entries = runEntries(answers, phase).mapNotNull { it.session }
    val mapped = lift.program.days.filter { it.kind != ProgramDayKind.REST }.mapIndexed { index, day ->
        day.id to entries[index % entries.size]
    }.toMap()
    fun fits(day: ProgramDay, block: CardioBlock) = SessionBudget.seconds(day.copy(cardioBlock = block), answers.goal) <= budget
    val long = lift.program.days.firstNotNullOfOrNull { day ->
        if (mapped[day.id] != RunSession.LONG) null
        else capped(template(RunSession.LONG)).takeIf { fits(day, it) }
    }
    val reasons = lift.reasons.filterNot { it is PlanReason.OverBudget }.toMutableList()
    val sessions = mutableMapOf<String, RunSession>()
    val days = lift.program.days.map { day ->
        val mappedSession = mapped[day.id] ?: return@map day
        var session = mappedSession
        var original: CardioBlock? = if (session == RunSession.EASY) null else template(session)
        var block = original?.let { if (session == RunSession.LADDER) it else capped(it) }
        if (block == null || !fits(day, block)) {
            if ((reservesRun && session != RunSession.LADDER) || session == RunSession.EASY) {
                session = RunSession.EASY
                val remaining = (budget - SessionBudget.seconds(day, answers.goal)) / 60 * 60
                if (reservesRun || remaining >= 1_200) {
                    original = template(session, minOf(if (reservesRun) 1_200 else remaining, easyLimit(long) ?: Int.MAX_VALUE))
                    block = capped(original)
                } else block = null
            } else if (!reservesRun) block = null
        }
        if (block != null) {
            sessions[day.id] = session
            if (block != original) reasons += PlanReason.RunCapped(day.id)
        }
        val combined = day.copy(cardioBlock = block,
            cardioRule = if (block != null && session == RunSession.LADDER) CardioRule(level) else null)
        val excess = SessionBudget.seconds(combined, answers.goal) - budget
        if (excess > 0) reasons += PlanReason.OverBudget(day.id, excess)
        combined
    }
    reasons += runReasons(run, phase, todayEpochDay)
    return lift.copy(program = Program(days), reasons = reasons, goal = runGoal(run),
        phase = phase.takeIf { sessions.isNotEmpty() }, sessions = sessions)
}

private fun relabel(draft: PlanDraft, ids: Map<String, String>): PlanDraft = draft.copy(
    program = Program(draft.program.days.map { it.copy(id = ids.getValue(it.id)) }),
    working = draft.working.mapKeys { (key, _) -> key.copy(dayId = ids.getValue(key.dayId)) },
    sessions = draft.sessions.mapKeys { (id, _) -> ids.getValue(id) },
    reasons = draft.reasons.map { reason ->
        when (reason) {
            is PlanReason.ShortSession -> reason.copy(dayId = ids.getValue(reason.dayId))
            is PlanReason.OverBudget -> reason.copy(dayId = ids.getValue(reason.dayId))
            is PlanReason.RunCapped -> reason.copy(dayId = ids.getValue(reason.dayId))
            PlanReason.DateTooClose, is PlanReason.GoalCheck -> reason
        }
    },
)
