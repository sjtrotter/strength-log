package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.TodayTarget
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.di.CivilDay
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

/**
 * The one observed projection of *today* — the open run or suggested day, its sets, their
 * done flags and the display unit — that every glance surface reads. The watch
 * publisher ([WearSyncPublisher]) mirrors it onto the Data Layer; the
 * home-screen widget ([cloud.trotter.log.strength.widget.TodayWidgetUpdater])
 * renders from it. Neither consumer re-derives "which day is today" or counts
 * sets of its own (glance-surfaces.md §4.2).
 *
 * Null suppresses publication during restore or when no day exists. A blank
 * day id explicitly clears the watch when all plans are paused.
 *
 * [WatchSnapshot.revision] is a placeholder 0 here — only the publisher stamps a
 * real revision, at publish time, so dedupe upstream compares content alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodaySnapshotSource(
    private val repo: TrackerRepository,
    @CivilDay private val today: Flow<LocalDate>,
    private val cardioAckLog: CardioAckLog,
    private val editAckLog: EditAckLog,
) {

    val snapshots: Flow<WatchSnapshot?> = flow {
        repo.runPlanSettingsFixupIfNeeded()
        emitAll(repo.todayTargetFlow.flatMapLatest { target ->
            if (target == TodayTarget.NothingOnToday) return@flatMapLatest combine(
                repo.unitFlow, cardioAckLog.acksFlow, editAckLog.processedEditsFlow,
            ) { unit, cardio, edits ->
                WatchSnapshot(
                    revision = 0L, suggestedDayId = "", unit = unit.name.lowercase(),
                    day = WatchDay("", "", 0, emptyList()),
                    cardioAckStamp = cardio.highest, cardioAckedStamps = cardio.recent,
                    processedEditStamps = edits,
                )
            }
            val day = target as? TodayTarget.Day
            val plan = day?.plan
            val dayId = day?.dayId
            if (plan == null || dayId == null) {
                flowOf(null)
            } else {
                val program = combine(
                    repo.programFor(plan.id),
                    repo.slotsFor(plan.id, dayId),
                    repo.dayTracksFlow(plan.id, dayId),
                    repo.openRunFlow,
                ) { program, slots, tracks, open ->
                    Triple(program, slots, tracks) to open
                }
                // restSettingsFlow rides the context combine so a Setup edit (master
                // toggle or a duration) republishes immediately — the watch never
                // counts a stale rest for longer than one sync hop.
                val context = combine(
                    repo.wizardAnswersFor(plan.id),
                    repo.catalogFlow,
                    repo.unitFlow,
                    repo.restSettingsFlow,
                ) { answers, catalog, unit, rest ->
                    Context(answers.config, catalog, unit, rest, answers.equipment)
                }
                combine(
                    program, context, repo.cardioSessionsFlow, today,
                    combine(cardioAckLog.acksFlow, editAckLog.processedEditsFlow) { cardio, edits ->
                        cardio to edits
                    },
                ) { (programState, open), ctx, cardioHistory, civilDate, (cardioAck, processedEdits) ->
                    val (prog, slots, tracks) = programState
                    val loggedCardio = cardioHistory.firstOrNull { session ->
                        session.planId == plan.id && session.dayId == dayId &&
                            Instant.ofEpochMilli(session.completedAt).atZone(ZoneId.systemDefault()).toLocalDate() == civilDate
                    }
                    WatchSnapshotBuilder.build(
                        program = prog,
                        suggestedDayId = dayId,
                        slots = slots,
                        logs = tracks.tracks,
                        cfg = ctx.cfg,
                        catalog = ctx.catalog,
                        unit = ctx.unit,
                        revision = 0L,
                        restSettings = ctx.restSettings,
                        equipment = ctx.equipment,
                        loggedCardio = loggedCardio,
                        cardioAckStamp = cardioAck.highest,
                        cardioAckedStamps = cardioAck.recent,
                        planId = plan.id,
                        planName = plan.name,
                        runId = tracks.runId,
                        runStartedAt = open?.takeIf { tracks.runOpen && it.id == tracks.runId }?.startedAt,
                        retiredRoundsDone = tracks.retiredRoundsDone.takeIf { it > 0 },
                        processedEditStamps = processedEdits,
                    )
                }
            }
        })
    }

    /** The non-program half of a snapshot's inputs, combined separately so the
     *  program flows and the preference flows conflate independently. */
    private data class Context(
        val cfg: LifterConfig,
        val catalog: ExerciseCatalog,
        val unit: WeightUnit,
        val restSettings: RestSettings,
        val equipment: Set<Equipment>,
    )
}
