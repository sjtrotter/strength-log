package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.serialization.setupAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.SessionBudget
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.TestPrep
import cloud.trotter.log.strength.domain.planner.easyRunSeconds
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.CardioHistory
import cloud.trotter.log.strength.domain.progression.CardioMeasures
import cloud.trotter.log.strength.domain.progression.CardioOutcome
import cloud.trotter.log.strength.domain.progression.EasyRunContext
import cloud.trotter.log.strength.domain.progression.LadderContext
import cloud.trotter.log.strength.domain.progression.LoggedCardio
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.progression.SprintContext
import cloud.trotter.log.strength.domain.progression.SprintDose
import cloud.trotter.log.strength.domain.progression.SprintRecord
import cloud.trotter.log.strength.domain.progression.daysSince
import cloud.trotter.log.strength.domain.progression.lastMaximalEpochDay
import cloud.trotter.log.strength.domain.progression.spacingDays
import cloud.trotter.log.strength.domain.progression.sprintDecision
import cloud.trotter.log.strength.domain.progression.sprintFirstWeeks
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.Instant
import java.time.ZoneId

/** Plan inputs a ruled cardio day needs at Start and Finish: stored rules with their exercises (the judged day's pre-write
 *  rule on replay), the rotation count before Finish wraps it, the plan goal, the careful group, the zone dating
 *  sessions, and the setup answers. */
data class CardioPlan(val program: Program, val cyclesCompleted: Int, val goal: PlanGoal?, val careful: Boolean,
                      val zone: ZoneId, val setup: SetupAnswers? = null) {
    companion object {}
}

/** Plan inputs from rows the caller holds: the program as stored, the rotation count, goal, setup answers and careful
 *  group, dated in [zone]. */
fun CardioPlan.Companion.of(plan: PlanEntity, program: Program, unit: WeightUnit, zone: ZoneId): CardioPlan {
    val setup = plan.setupAnswers(unit)
    return CardioPlan(program, plan.cyclesCompleted, PlanGoalJson.decode(plan.goalJson), setup?.careful == true, zone, setup)
}

data class SprintStart(val block: CardioBlock, val dose: SprintDose, val daysSince: Long?, val spacingDays: Int,
                       val reason: SpacingReason, val nineTenths: Boolean)
enum class SpacingReason { FORTY_OR_OLDER, HAMSTRING_STRAIN, STARTING_OUT, FOR_NOW }

/** What a logged cardio session earns its day, and the block a day opens with. The cardio commit, a later distance,
 *  the chip and every opening read it, so they agree. Pure: callers pass rows they already hold. */
object CardioJudge {
    /** The block [day] opens with at [nowMillis] (design §3.2 Phase and taper, Run cap): the taper from [plan]'s goal
     *  date, then the run cap against the family's sessions completed by then. A non-sprint day kept to plan opens as stored.
     *  Null when [day] has no block or a sprint day has no [sprint] plan inputs. */
    fun opening(day: ProgramDay, plan: PlanEntity, sessions: List<CardioSessionEntity>,
                sets: Map<Long, List<CardioSetResult>>, nowMillis: Long, todayEpochDay: Long, sprint: CardioPlan? = null): CardioBlock? {
        val stored = day.cardioToRun ?: return null
        if (day.cardioRule?.session?.sprint == true) {
            return sprint?.let { sprintStart(day, plan, sessions, sets, nowMillis, todayEpochDay, it)?.block }
        }
        if (day.cardioProgress.keepToPlan) return stored
        val family = CardioFamily.of(stored.activity).activities.map { it.name }
        val prior = sessions.filter { it.activity in family && it.completedAt <= nowMillis }
            .sortedWith(compareBy({ it.completedAt }, { it.id }))
        val recent = CardioHistory.of(prior.map { LoggedCardio(it.completedAt, sets[it.id].orEmpty()) }, nowMillis)
        val ctx = Progression.CardioContext(todayEpochDay, measure = null, percent = 0,
            runCap = plan.planSettings.runCap)
        return Progression.cardioAtStart(stored, day.cardioRule, recent, PlanGoalJson.decode(plan.goalJson), ctx)
    }

    /** What a sprint day opens with and why ([SprintStart]). Null on a day that is not a sprint day. */
    fun sprintStart(day: ProgramDay, plan: PlanEntity, sessions: List<CardioSessionEntity>,
                    sets: Map<Long, List<CardioSetResult>>, nowMillis: Long, todayEpochDay: Long, inputs: CardioPlan): SprintStart? {
        val rule = day.cardioRule ?: return null
        if (rule.session?.sprint != true) return null
        val stored = day.cardioToRun ?: return null
        val family = CardioFamily.of(stored.activity).activities.map { it.name }
        val prior = sessions.filter { it.activity in family && it.completedAt <= nowMillis }
            .sortedWith(compareBy({ it.completedAt }, { it.id }))
        val decision = sprintDecision(rule, inputs.context(day.id, plan.id, prior, sets, todayEpochDay), stored.activity)
        val records = inputs.records(plan.id, prior, sets)
        val setup = inputs.setup
        val firstWeeks = sprintFirstWeeks(inputs.program, setup?.experience == LiftExperience.NEW)
        val reason = when {
            setup?.sprint?.fortyOrOlder == true || setup?.olderAdult == true -> SpacingReason.FORTY_OR_OLDER
            setup?.sprint?.hamstringStrain == true -> SpacingReason.HAMSTRING_STRAIN
            firstWeeks -> SpacingReason.STARTING_OUT
            else -> SpacingReason.FOR_NOW
        }
        return SprintStart(decision.block, decision.dose, daysSince(lastMaximalEpochDay(inputs.program, records), todayEpochDay),
            spacingDays(firstWeeks, inputs.careful), reason,
            inputs.careful && decision.dose != SprintDose.SUBSTITUTE &&
                (rule.intro > 0 || decision.dose == SprintDose.RESTART) && decision.block.sets.any { it.hard })
    }

    /** The block [session] ran ([opening]) and next time's [outcome]. */
    data class Judged(val opening: CardioBlock, val outcome: CardioOutcome)

    /** Null when [day] has no block or a non-sprint day is kept to plan, or when [session] has no readable activity or sets.
     *  Ruled run and sprint days need [plan]; sprint days also need an activity in [CardioFamily.RUN].
     *  Ladder days take the session's local Start date and the busy test ([RunWalkLadder.busy]).
     *  Non-ladder run days need a progress measure. The opening guard lives in the repository.
     *  [sessions] supplies the family's sessions strictly before this session by (completedAt, id).
     *  Sprint replay uses Start's local date, the prior ledger and the pre-write rule. Other days' rules can drift
     *  after edits; structural credit denies credit for a shortened Start that becomes normal on replay.
     *  Trial admission can still drift after edits. Repository undo and newest-session gates bound replay. */
    fun judge(day: ProgramDay, session: CardioSessionEntity, sessions: List<CardioSessionEntity>,
              sets: Map<Long, List<CardioSetResult>>, runCap: Boolean, plan: CardioPlan? = null): Judged? {
        if (day.cardioBlock == null || (day.cardioProgress.keepToPlan && day.cardioRule?.session?.sprint != true)) return null
        val activity = CardioActivity.entries.firstOrNull { it.name == session.activity } ?: return null
        val results = sets[session.id].orEmpty().ifEmpty { return null }
        val opening = CardioBlock(activity, results.map { it.spec })
        val sprintDay = day.cardioRule?.session?.sprint == true
        if (day.cardioRule != null && plan == null) return null
        if (sprintDay && CardioFamily.of(activity) != CardioFamily.RUN) return null
        val ladder = day.cardioRule?.ladderLevel != null
        val measure = if (sprintDay || ladder) null else {
            CardioMeasures.resolve(opening, day.cardioProgress.measure) ?: return null
        }
        val family = CardioFamily.of(activity).activities.map { it.name }
        val order = compareBy<CardioSessionEntity>({ it.completedAt }, { it.id })
        val prior = sessions.filter { it.activity in family && order.compare(it, session) < 0 }.sortedWith(order)
        val recent = CardioHistory.of(prior.map { LoggedCardio(it.completedAt, sets[it.id].orEmpty()) }, session.completedAt)
        val effort = CardioEffort.entries.firstOrNull { it.name == session.effort }
        val percent = if (sprintDay || ladder) 0 else {
            day.cardioProgress.increasePercent ?: CardioMeasures.defaultPercent(requireNotNull(measure))
        }
        val today = if (sprintDay) localStart(session, requireNotNull(plan).zone)
            else Math.floorDiv(session.completedAt, 86_400_000L)
        val context = if (sprintDay && plan != null) {
            plan.context(day.id, session.planId, prior, sets, today)
        } else null
        val ladderContext = if (ladder) {
            val zone = requireNotNull(plan).zone
            val localDay = localStart(session, zone)
            LadderContext(localDay, RunWalkLadder.busy(prior.map { localStart(it, zone) } + localDay, localDay))
        } else null
        val setup = plan?.setup
        val programDay = plan?.program?.days?.firstOrNull { it.id == day.id }?.copy(cardioBlock = day.cardioToRun)
        val budget = if (day.cardioRule != null && !ladder && setup != null && programDay != null) {
            SessionBudget.cardioBudget(programDay, setup.minutes, setup.slotGoal)
        } else null
        val easy = if (day.cardioRule?.session == RunSession.EASY && day.kind == ProgramDayKind.CARDIO &&
            setup != null && programDay != null) {
            val inputs = requireNotNull(plan)
            val long = inputs.program.days.firstOrNull { it.cardioRule?.session == RunSession.LONG }?.cardioToRun
            EasyRunContext(easyRunSeconds(setup.minutes, programDay.exercises.isNotEmpty(), long,
                TestPrep.reservedSeconds(programDay, setup.slotGoal)), inputs.cyclesCompleted)
        } else null
        val ctx = Progression.CardioContext(today, measure, percent, runCap = runCap, sprint = context,
            easy = easy, budgetSeconds = budget, ladder = ladderContext)
        val outcome = Progression.cardioAfterSession(opening, day.cardioRule, results, effort, recent, ctx)
        val held = day.cardioRule == null && outcome.block.sets.map { it.role } != opening.sets.map { it.role }
        return Judged(opening, if (held) CardioOutcome(opening, day.cardioRule, CardioChange.Repeat) else outcome)
    }

    private fun localStart(session: CardioSessionEntity, zone: ZoneId): Long =
        Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate().toEpochDay()

    private fun CardioPlan.context(dayId: String, planId: Long?, prior: List<CardioSessionEntity>,
                                   sets: Map<Long, List<CardioSetResult>>, today: Long): SprintContext {
        return SprintContext.ofHistory(program, dayId, records(planId, prior, sets), cyclesCompleted, goal, today, careful)
    }

    private fun CardioPlan.records(planId: Long?, prior: List<CardioSessionEntity>,
                                   sets: Map<Long, List<CardioSetResult>>): List<SprintRecord> =
        prior.filter { it.planId == planId && it.dayId != null }.map {
            SprintRecord(requireNotNull(it.dayId), localStart(it, zone), sets[it.id].orEmpty())
        }
}
