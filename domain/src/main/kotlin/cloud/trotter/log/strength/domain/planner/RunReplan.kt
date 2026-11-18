package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.WeekLoad
import cloud.trotter.log.strength.domain.progression.plannedSeconds
import cloud.trotter.log.strength.domain.progression.withoutLastRound
import kotlin.math.ceil
import kotlin.math.floor

/** The phase gate at a rotation wrap (design §3.1.2, revision 48). Pure: the caller supplies the stored program
 *  and the log. Null when nothing moves or the weekly guard cannot fit; a later wrap can try again. */
object RunReplan {
    /** Practice, guided by audit 2026-11-13 row 4 (Damsted 2019, Nielsen 2014; weak). */
    const val WEEK_RISE_PERCENT = 20
    /** Practice: the shortest easy run the weekly guard leaves. */
    const val EASY_FLOOR_SECONDS = 600

    data class Replanned(val program: Program, val phase: RunPhase)

    fun next(stored: Program, answers: SetupAnswers, phase: RunPhase, known: KnownLoads, todayEpochDay: Long?,
             week: WeekLoad, secondsPerMetre: Double?): Replanned? {
        if (phase == RunPhase.LADDER && stored.days.any { it.cardioRule?.ladderLevel != null }) return null
        val draft = Planner.plan(answers, known, todayEpochDay, if (phase == RunPhase.LADDER) RunPhase.BASE else phase)
        val nextPhase = draft.phase?.takeIf { it > phase } ?: return null
        val previous = stored.days.associateBy { it.id }
        if (previous.keys.any { id -> draft.program.days.none { it.id == id } }) return null
        val merged = mutableListOf<ProgramDay>()
        for (day in draft.program.days) {
            val old = previous[day.id]
            val allowed = if (old == null) day.kind in listOf(ProgramDayKind.CARDIO, ProgramDayKind.REST)
                else old.kind == day.kind || (old.kind == ProgramDayKind.REST && day.kind == ProgramDayKind.CARDIO)
            if (!allowed) return null
            merged += when {
                old == null -> day.copy(exercises = emptyList())
                old.cardioProgress.keepToPlan -> old
                old.kind == ProgramDayKind.STRENGTH -> old.copy(cardioBlock = day.cardioBlock, cardioRule = day.cardioRule,
                    cardioProgress = old.cardioProgress.copy(working = null))
                else -> old.copy(kind = day.kind, title = day.title, cardioBlock = day.cardioBlock, cardioRule = day.cardioRule,
                    cardioProgress = old.cardioProgress.copy(working = null))
            }
        }
        val pace = secondsPerMetre ?: 0.36
        require(pace.isFinite() && pace > 0)
        val long = merged.firstOrNull { it.cardioRule?.session == RunSession.LONG }?.cardioToRun
        val longestEasy = stored.days.filter { it.cardioRule?.session == RunSession.EASY }
            .mapNotNull { it.cardioToRun?.let { block -> plannedSeconds(block, pace) } }.maxOrNull()
        for (index in merged.indices) {
            var day = merged[index]
            if (day.cardioProgress.keepToPlan) continue
            if (day.cardioRule?.session == RunSession.EASY) {
                val old = previous[day.id]?.takeIf { it.cardioRule?.session == RunSession.EASY }
                    ?.cardioToRun?.let { plannedSeconds(it, pace) } ?: longestEasy
                val end = day.workEnd() as? Measure.Time
                if (end != null) day = day.withEnd(Measure.Time(minOf(end.seconds,
                    easyRunSeconds(answers.minutes, day.exercises.isNotEmpty(), long), old ?: Int.MAX_VALUE)))
            }
            if (day.kind == ProgramDayKind.CARDIO && day.exercises != draft.program.days[index].exercises) {
                while (day.workCount() > 1 && SessionBudget.seconds(day, answers.slotGoal) > answers.minutes * 60) {
                    day = day.copy(cardioBlock = requireNotNull(day.cardioBlock).withoutLastRound())
                }
            }
            merged[index] = day
        }
        fun total(days: List<ProgramDay>) = Program(days).rotationDays.sumOf { it.cardioToRun?.let { b -> plannedSeconds(b, pace) } ?: 0 }
        val basis = minOf(total(stored.days), week.total.seconds ?: return null)
        val allowedSeconds = basis.toLong() * (100 + WEEK_RISE_PERCENT) / 100
        fun over() = total(merged) > allowedSeconds
        fun cutPass(cut: (ProgramDay) -> ProgramDay) {
            do {
                var changed = false
                for (index in merged.indices) {
                    if (!over()) return
                    val day = merged[index]
                    if (day.cardioProgress.keepToPlan) continue
                    val next = cut(day)
                    if (next != day) { merged[index] = next; changed = true }
                }
            } while (over() && changed)
        }
        cutPass { day ->
            val end = day.workEnd() as? Measure.Time
            if (day.cardioRule?.session == RunSession.EASY && end != null && end.seconds > EASY_FLOOR_SECONDS) {
                day.withEnd(Measure.Time(maxOf(EASY_FLOOR_SECONDS, end.seconds - 60)))
            } else day
        }
        cutPass { day ->
            if (day.workCount() > 1) day.copy(cardioBlock = requireNotNull(day.cardioBlock).withoutLastRound()) else day
        }
        for (index in merged.indices) {
            if (!over()) break
            val day = merged[index]
            if (day.cardioProgress.keepToPlan || day.cardioRule?.session != RunSession.LONG) continue
            val oldEnd = stored.days.firstOrNull { it.cardioRule?.session == RunSession.LONG }?.workEnd()
            val excess = total(merged) - allowedSeconds
            val end = when (val current = day.workEnd()) {
                is Measure.Time -> {
                    val oldSeconds = when (oldEnd) {
                        is Measure.Time -> oldEnd.seconds
                        is Measure.Distance -> ceil(oldEnd.metres * pace).toInt()
                        null -> 0
                    }
                    val minimum = maxOf(oldSeconds, week.longest.seconds ?: 0)
                    Measure.Time(minOf(current.seconds, maxOf(minimum, floor((current.seconds - excess) / 60.0).toInt() * 60)))
                }
                is Measure.Distance -> {
                    val oldMetres = when (oldEnd) {
                        is Measure.Distance -> oldEnd.metres
                        is Measure.Time -> ceil(oldEnd.seconds / pace).toInt()
                        null -> 0
                    }
                    val minimum = maxOf(oldMetres, week.longest.metres ?: ceil((week.longest.seconds ?: 0) / pace).toInt())
                    Measure.Distance(minOf(current.metres, maxOf(minimum, floor((current.metres - excess / pace) / 100).toInt() * 100)))
                }
                null -> continue
            }
            merged[index] = day.withEnd(end)
        }
        if (over()) return null
        return Replanned(Program(merged), nextPhase)
    }

    private fun ProgramDay.workEnd(): Measure? = cardioToRun?.sets?.firstOrNull { it.role == CardioRole.WORK }?.end
    private fun ProgramDay.workCount(): Int = cardioToRun?.sets?.count { it.role == CardioRole.WORK } ?: 0
    private fun ProgramDay.withEnd(end: Measure): ProgramDay = copy(cardioBlock = requireNotNull(cardioBlock).let { block ->
        block.copy(sets = block.sets.map { if (it.role == CardioRole.WORK) it.copy(end = end) else it })
    })
}
