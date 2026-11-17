package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.CardioHistory
import cloud.trotter.log.strength.domain.progression.CardioMeasures
import cloud.trotter.log.strength.domain.progression.CardioOutcome
import cloud.trotter.log.strength.domain.progression.LoggedCardio
import cloud.trotter.log.strength.domain.progression.SprintContext
import cloud.trotter.log.strength.domain.progression.SprintRecord
import cloud.trotter.log.strength.domain.progression.Progression
import java.time.Instant
import java.time.ZoneId

/** Sprint context beyond the day (design §4.2): stored rules (the judged day's pre-write rule on replay),
 *  rotation count before Finish wraps it, plan goal, careful group and the zone dating sessions by local start. */
data class SprintPlan(val program: Program, val cyclesCompleted: Int, val goal: PlanGoal?, val careful: Boolean, val zone: ZoneId)

/** What a logged cardio session earns its day, and the block a day opens with. The cardio commit, a later distance,
 *  the chip and every opening read it, so they agree. Pure: callers pass rows they already hold. */
object CardioJudge {
    /** The block [day] opens with at [nowMillis] (design §3.2 Phase and taper, Run cap): the taper from [plan]'s goal
     *  date, then the run cap against the family's sessions completed by then. A day kept to plan opens as stored.
     *  Null when [day] has no block or a sprint day has no [sprint] plan inputs. */
    fun opening(day: ProgramDay, plan: PlanEntity, sessions: List<CardioSessionEntity>,
                sets: Map<Long, List<CardioSetResult>>, nowMillis: Long, todayEpochDay: Long, sprint: SprintPlan? = null): CardioBlock? {
        val stored = day.cardioToRun ?: return null
        if (day.cardioProgress.keepToPlan) return stored
        val sprintDay = day.cardioRule?.session?.sprint == true
        if (sprintDay && sprint == null) return null
        val family = CardioFamily.of(stored.activity).activities.map { it.name }
        val prior = sessions.filter { it.activity in family && it.completedAt <= nowMillis }
            .sortedWith(compareBy({ it.completedAt }, { it.id }))
        val recent = CardioHistory.of(prior.map { LoggedCardio(it.completedAt, sets[it.id].orEmpty()) }, nowMillis)
        val context = if (sprintDay && sprint != null) sprint.context(day.id, plan.id, prior, sets, todayEpochDay) else null
        val ctx = Progression.CardioContext(todayEpochDay, measure = null, percent = 0,
            runCap = plan.planSettings.runCap, sprint = context)
        return Progression.cardioAtStart(stored, day.cardioRule, recent, PlanGoalJson.decode(plan.goalJson), ctx)
    }

    /** The block [session] ran ([opening]) and next time's [outcome]. */
    data class Judged(val opening: CardioBlock, val outcome: CardioOutcome)

    /** Null when [day] has no block, is kept to plan or on the ladder, or when [session] has no readable activity, no
     *  sets, or (on non-sprint days) no progress measure; sprint days require [sprint] plan inputs and an activity in
     *  [CardioFamily.RUN]. [sessions] may hold anything: the family's sessions before [session] are taken from it;
     *  [sets] maps ids to their results. Sprint replay anchors to Start's local date, the ledger
     *  strictly before this session and stored rules (the judged day's pre-write rule on re-judgment).
     *  floorDiv(completedAt, 86_400_000) used the UTC Finish day; a US evening run is already tomorrow there.
     *  Other maximal days' current rules can drift after a plan edit or Reset day. Structural credit denies credit
     *  when a shortened Start becomes normal on replay. Such an edit can also flip trial admission and write or drop
     *  sprintTrialCycle; this residual is accepted. Repository undo and newest-session gates bound replay. */
    fun judge(day: ProgramDay, session: CardioSessionEntity, sessions: List<CardioSessionEntity>,
              sets: Map<Long, List<CardioSetResult>>, runCap: Boolean, sprint: SprintPlan? = null): Judged? {
        if (day.cardioBlock == null || day.cardioProgress.keepToPlan || day.cardioRule?.ladderLevel != null) return null
        val activity = CardioActivity.entries.firstOrNull { it.name == session.activity } ?: return null
        val results = sets[session.id].orEmpty().ifEmpty { return null }
        val opening = CardioBlock(activity, results.map { it.spec })
        val sprintDay = day.cardioRule?.session?.sprint == true
        if (sprintDay && (sprint == null || CardioFamily.of(activity) != CardioFamily.RUN)) return null
        val measure = if (sprintDay) null else {
            CardioMeasures.resolve(opening, day.cardioProgress.measure) ?: return null
        }
        val family = CardioFamily.of(activity).activities.map { it.name }
        val order = compareBy<CardioSessionEntity>({ it.completedAt }, { it.id })
        val prior = sessions.filter { it.activity in family && order.compare(it, session) < 0 }.sortedWith(order)
        val recent = CardioHistory.of(prior.map { LoggedCardio(it.completedAt, sets[it.id].orEmpty()) }, session.completedAt)
        val effort = CardioEffort.entries.firstOrNull { it.name == session.effort }
        val percent = if (sprintDay) 0 else {
            day.cardioProgress.increasePercent ?: CardioMeasures.defaultPercent(requireNotNull(measure))
        }
        val today = if (sprintDay) localStart(session, requireNotNull(sprint).zone)
            else Math.floorDiv(session.completedAt, 86_400_000L)
        val context = if (sprintDay && sprint != null) {
            sprint.context(day.id, session.planId, prior, sets, today)
        } else null
        val ctx = Progression.CardioContext(today, measure, percent, runCap = runCap, sprint = context)
        val outcome = Progression.cardioAfterSession(opening, day.cardioRule, results, effort, recent, ctx)
        val held = !sprintDay && outcome.block.sets.map { it.role } != opening.sets.map { it.role }
        return Judged(opening, if (held) CardioOutcome(opening, day.cardioRule, CardioChange.Repeat) else outcome)
    }

    private fun localStart(session: CardioSessionEntity, zone: ZoneId): Long =
        Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate().toEpochDay()

    private fun SprintPlan.context(dayId: String, planId: Long?, prior: List<CardioSessionEntity>,
                                   sets: Map<Long, List<CardioSetResult>>, today: Long): SprintContext {
        val records = prior.filter { it.planId == planId && it.dayId != null }.map {
            SprintRecord(requireNotNull(it.dayId), localStart(it, zone), sets[it.id].orEmpty())
        }
        return SprintContext.ofHistory(program, dayId, records, cyclesCompleted, goal, today, careful)
    }
}
