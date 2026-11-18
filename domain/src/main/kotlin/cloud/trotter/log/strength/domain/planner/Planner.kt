package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SlotSeeding

data class SlotKey(val dayId: String, val index: Int)

/** Keys for the "Why this plan" sheet (PR 6). Re-derived from the answers, never stored. */
sealed interface PlanReason {
    /** The day fits after ladder step [step] (1 to 8; 7 drops plyometrics and 8 the knee-flexion
     *  slot, only on sprint days). Step 0: the ladder left the day alone, but its minutes stop
     *  a weekly set floor (invariant 5). */
    data class ShortSession(val dayId: String, val step: Int) : PlanReason
    /** After the last ladder step the day is still [seconds] over; Your plan offers "Drop a lift" or "Longer sessions". */
    data class OverBudget(val dayId: String, val seconds: Int) : PlanReason
    /** The run cap shortened this day's session at creation. */
    data class RunCapped(val dayId: String) : PlanReason
    /** The date is closer than the remaining phases need; the plan still generates (§3.1.2). */
    data object DateTooClose : PlanReason
    /** Riegel predicts slower than the goal. */
    data class GoalCheck(val predictedSeconds: Int) : PlanReason
    /** A 65+ lifter with fewer than three lifting days got balance-only entries, so balance work reaches three days
     *  a week (audit 2026-11-13 decision 12, WHO 2020). Plan-level. */
    data object BalanceDays : PlanReason
    /** A 65+ lifter with fewer than three lifting days could add balance-only days to reach three a week; the setup
     *  screen offers them (audit 2026-11-13 decision 12, WHO 2020). Plan-level. */
    data object BalanceDaysOffered : PlanReason
}

data class PlanDraft(
    val program: Program,
    val working: Map<SlotKey, List<LoggedSet>>,
    val deloadEveryCycles: Int?,
    val reasons: List<PlanReason>,
    /** Event, test or sprint goal; null for lift-only plans (§3.1). */
    val goal: PlanGoal? = null,
    val phase: RunPhase? = null,
    /** Day id to session, for every day with a cardio block. */
    val sessions: Map<String, RunSession> = emptyMap(),
)

data class DayDraft(val day: ProgramDay, val working: Map<Int, List<LoggedSet>>, val reasons: List<PlanReason>)

object Planner {
    /** Deterministic for (answers, known, today). Never reads the clock or custom exercises.
     *  A plan built from the log's [known] starts at the first unmet gate, so a later [PlanDraft.phase] than the
     *  stored plan's is a passed gate (design §3.1.2). [atLeast] BASE on a ladder-only path asks for
     *  the goal's own plan after the ladder. */
    fun plan(answers: SetupAnswers, known: KnownLoads = KnownLoads.NONE, todayEpochDay: Long? = null,
             atLeast: RunPhase? = null): PlanDraft {
        require(atLeast == null || atLeast in answers.run.goal.phases ||
            (atLeast == RunPhase.BASE && answers.run.goal.phases.isEmpty() && answers.run.phases == listOf(RunPhase.LADDER)))
        require(todayEpochDay != null || answers.branch == PlanBranch.LIFT || answers.run.dateEpochDay == null)
        return when (answers.branch) {
            PlanBranch.LIFT -> liftDraft(answers, known).withBalanceDays(answers, known)
            PlanBranch.RUN -> runDraft(answers, known, todayEpochDay, answers.slotGoal, atLeast)
            PlanBranch.BOTH -> if (answers.both.sprint) sprintDraft(answers, known, answers.slotGoal)
                else bothDraft(answers, known, todayEpochDay, atLeast)
            PlanBranch.SPRINT -> sprintDraft(answers, known, answers.slotGoal)
        }
    }

    /** The smallest minutes answer at or above [answers]'s whose plan needs no ladder step and is not over budget;
     *  null when even 90 minutes trims (design §3.4). A step-0 ShortSession (a weekly floor stop) does not count. */
    fun fullDoseMinutes(answers: SetupAnswers, known: KnownLoads = KnownLoads.NONE, todayEpochDay: Long? = null): Int? =
        SetupAnswers.MINUTES.firstOrNull { minutes ->
            minutes >= answers.minutes && plan(answers.copy(minutes = minutes), known, todayEpochDay).reasons.none {
                (it is PlanReason.ShortSession && it.step >= 1) || it is PlanReason.OverBudget
            }
        }

    /** One day of [plan], for Reset day to plan. Null for an id the answers do not produce. */
    fun day(answers: SetupAnswers, dayId: String, known: KnownLoads = KnownLoads.NONE, todayEpochDay: Long? = null): DayDraft? {
        val draft = plan(answers, known, todayEpochDay)
        val day = draft.program.days.firstOrNull { it.id == dayId } ?: return null
        return DayDraft(day, draft.working.filterKeys { it.dayId == dayId }.mapKeys { it.key.index },
            draft.reasons.filter { reason ->
                when (reason) {
                    is PlanReason.ShortSession -> reason.dayId == dayId
                    is PlanReason.OverBudget -> reason.dayId == dayId
                    is PlanReason.RunCapped -> reason.dayId == dayId
                    PlanReason.DateTooClose, is PlanReason.GoalCheck, PlanReason.BalanceDays, PlanReason.BalanceDaysOffered -> false
                }
            })
    }

    fun defaultMains(equipment: Set<Equipment>, excluded: Set<String> = emptySet()): List<String> =
        mainPatterns.indices.mapNotNull { rowDefault(it, equipment, excluded) }

    /** Your lifts (design §2.2): per main row (squat, hinge, horizontal press, overhead press, pull), every lift the
     *  planner takes as that row's main with this gear, the row's default first. A row nothing fills is empty. */
    fun mainChoices(equipment: Set<Equipment>, excluded: Set<String> = emptySet()): List<List<String>> =
        mainPatterns.indices.map { row ->
            val default = rowDefault(row, equipment, excluded)
            val others = mainPatterns[row].flatMap(ExerciseLibrary::byPattern)
                .filter { mainCandidate(it, equipment, excluded) && it.tracking != TrackingType.TIMED }
                .sortedBy { if (it.tracking == TrackingType.WEIGHTED) 0 else 1 }
                .map { it.id }
            listOfNotNull(default) + (others - setOfNotNull(default))
        }

    private fun rowDefault(row: Int, equipment: Set<Equipment>, excluded: Set<String>): String? {
        val defaults = listOf("bb_back_squat", "trap_dl", "bb_bench", "ohp", "bb_row")
        val preferred = ExerciseLibrary.get(defaults[row]).takeIf { it.availableWith(equipment, excluded) }
        val fallback = mainPatterns[row].firstNotNullOfOrNull { pattern ->
            val candidates = ExerciseLibrary.byPattern(pattern).filter { mainCandidate(it, equipment, excluded) }
            candidates.firstOrNull { it.tracking == TrackingType.WEIGHTED }
                ?: candidates.firstOrNull { it.tracking == TrackingType.REPS }
        }
        return (preferred ?: fallback)?.id
    }

    private fun mainCandidate(entry: ExerciseEntry, equipment: Set<Equipment>, excluded: Set<String>): Boolean {
        val bodyweight = ExerciseLibrary.bodyweightPairFor(entry.id)?.let(ExerciseLibrary::get)
        return entry.availableWith(equipment, excluded) && entry.equipment != bodyweight?.equipment
    }
}

private val mainPatterns = listOf(
    listOf(MovementPattern.SQUAT_BILATERAL), listOf(MovementPattern.HINGE), listOf(MovementPattern.H_PUSH),
    listOf(MovementPattern.V_PUSH), listOf(MovementPattern.H_PULL, MovementPattern.V_PULL),
)

internal fun mainRow(pattern: MovementPattern): Int = mainPatterns.indexOfFirst { pattern in it }

internal fun liftDraft(
    answers: SetupAnswers,
    known: KnownLoads,
    budgetSeconds: Int = answers.minutes * 60,
): PlanDraft {
    val mains = resolveMains(answers)
    val reasons = mutableListOf<PlanReason>()
    val fittedDays = layout(answers, mains).map { day ->
        if (day.kind != ProgramDayKind.STRENGTH) day else {
            val (fitted, dayReasons) = fitDay(day, answers, budgetSeconds)
            reasons += dayReasons
            fitted
        }
    }
    val touched = reasons.filterIsInstance<PlanReason.ShortSession>().map { it.dayId }.toSet()
    val (balancedDays, volumeReasons) = VolumeBalance.balance(fittedDays, answers, mains.map { it.id }.toSet(), touched, budgetSeconds)
    reasons += volumeReasons
    val (days, working) = seedAll(balancedDays, answers, known)
    return PlanDraft(Program(days), working,
        if (answers.experience == LiftExperience.A_YEAR_OR_MORE) 6 else null, reasons)
}

internal fun resolveMains(answers: SetupAnswers): List<ExerciseEntry> {
    val defaults = Planner.defaultMains(answers.equipment, answers.excluded)
    val mains = (answers.mains ?: defaults).mapNotNull { id ->
        val entry = requireNotNull(ExerciseLibrary.find(id)) { "Unknown main: $id" }
        require(entry.availableWith(answers.equipment, answers.excluded)) { "Unavailable main: $id" }
        require(mainRow(entry.pattern) >= 0) { "Not a main row: $id" }
        if (entry.tracking == TrackingType.TIMED) {
            defaults.map(ExerciseLibrary::get).firstOrNull { mainRow(it.pattern) == mainRow(entry.pattern) }
        } else entry
    }.sortedBy { mainRow(it.pattern) }
    require(mains.map { mainRow(it.pattern) }.distinct().size == mains.size) { "Choose one main per row" }
    return mains
}

internal fun seedAll(
    days: List<ProgramDay>,
    answers: SetupAnswers,
    known: KnownLoads,
): Pair<List<ProgramDay>, Map<SlotKey, List<LoggedSet>>> {
    val seedKnown = known.copy(lastWorking = known.lastWorking + answers.recent.mapValues { (_, number) ->
        LoggedSet(number.weightLb, number.reps, SetKind.WORK, rir = number.effort.rir)
    })
    val working = mutableMapOf<SlotKey, List<LoggedSet>>()
    val seededDays = days.map { day ->
        day.copy(exercises = day.exercises.mapIndexed { index, slot ->
            val seeded = SlotSeeding.rows(slot, requireNotNull(slot.rule), seedKnown, answers.steps, answers.unit)
            working[SlotKey(day.id, index)] = seeded.rows
            slot.copy(rule = seeded.rule)
        })
    }
    return seededDays to working
}
