package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.CardioHistory
import cloud.trotter.log.strength.domain.progression.CardioMeasures
import cloud.trotter.log.strength.domain.progression.CardioOutcome
import cloud.trotter.log.strength.domain.progression.LoggedCardio
import cloud.trotter.log.strength.domain.progression.Progression

/** What a logged cardio session earns its day, and the block a day opens with. The cardio commit, a later distance,
 *  the chip and every opening read it, so they agree. Pure: callers pass rows they already hold. */
object CardioJudge {
    /** The block [day] opens with at [nowMillis] (design §3.2 Phase and taper, Run cap): the taper from [plan]'s goal
     *  date, then the run cap against the family's sessions completed by then. A day kept to plan opens as stored.
     *  Null when [day] has no block. */
    fun opening(day: ProgramDay, plan: PlanEntity, sessions: List<CardioSessionEntity>,
                sets: Map<Long, List<CardioSetResult>>, nowMillis: Long, todayEpochDay: Long): CardioBlock? {
        val stored = day.cardioToRun ?: return null
        if (day.cardioProgress.keepToPlan) return stored
        val family = CardioFamily.of(stored.activity).activities.map { it.name }
        val prior = sessions.filter { it.activity in family && it.completedAt <= nowMillis }
            .sortedWith(compareBy({ it.completedAt }, { it.id }))
        val recent = CardioHistory.of(prior.map { LoggedCardio(it.completedAt, sets[it.id].orEmpty()) }, nowMillis)
        // cardioAtStart reads only the day and the run cap.
        val ctx = Progression.CardioContext(todayEpochDay, measure = null, percent = 0)
        return Progression.cardioAtStart(stored, day.cardioRule, recent, PlanGoalJson.decode(plan.goalJson), ctx)
    }

    /** The block [session] ran ([opening]) and next time's [outcome]. */
    data class Judged(val opening: CardioBlock, val outcome: CardioOutcome)

    /** Null when [day] has no block, is kept to plan or on the ladder, or when [session] has no readable activity, no
     *  sets, or no progress measure. [sessions] may hold anything: the family's sessions before [session] are taken
     *  from it, and [sets] maps session ids to their results. */
    fun judge(day: ProgramDay, session: CardioSessionEntity, sessions: List<CardioSessionEntity>,
              sets: Map<Long, List<CardioSetResult>>): Judged? {
        if (day.cardioBlock == null || day.cardioProgress.keepToPlan || day.cardioRule?.ladderLevel != null) return null
        val activity = CardioActivity.entries.firstOrNull { it.name == session.activity } ?: return null
        val results = sets[session.id].orEmpty().ifEmpty { return null }
        val opening = CardioBlock(activity, results.map { it.spec })
        val measure = CardioMeasures.resolve(opening, day.cardioProgress.measure) ?: return null
        val family = CardioFamily.of(activity).activities.map { it.name }
        val order = compareBy<CardioSessionEntity>({ it.completedAt }, { it.id })
        val prior = sessions.filter { it.activity in family && order.compare(it, session) < 0 }.sortedWith(order)
        val recent = CardioHistory.of(prior.map { LoggedCardio(it.completedAt, sets[it.id].orEmpty()) }, session.completedAt)
        val effort = CardioEffort.entries.firstOrNull { it.name == session.effort }
        val percent = day.cardioProgress.increasePercent ?: CardioMeasures.defaultPercent(measure)
        // cardioAfterSession reads no date; the context's day is the session's UTC day.
        val ctx = Progression.CardioContext(Math.floorDiv(session.completedAt, 86_400_000L), measure, percent)
        val outcome = Progression.cardioAfterSession(opening, day.cardioRule, results, effort, recent, ctx)
        val held = outcome.block.sets.map { it.role } != opening.sets.map { it.role }
        return Judged(opening, if (held) CardioOutcome(opening, day.cardioRule, CardioChange.Repeat) else outcome)
    }
}
