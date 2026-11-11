package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.HistoryRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.TodayTarget
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.di.CivilDay
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.CardioRun
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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
    private val settings: SettingsStore,
    private val plans: PlanRepository,
    private val historyRepository: HistoryRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val cardioDraft: CardioDraftSource = CardioDraftSource(),
) {

    val snapshots: Flow<WatchSnapshot?> = flow {
        plans.runPlanSettingsFixupIfNeeded()
        emitAll(repo.todayTargetFlow.flatMapLatest { target ->
            if (target == TodayTarget.NothingOnToday) return@flatMapLatest combine(
                settings.unitFlow, cardioAckLog.acksFlow, editAckLog.processedEditsFlow,
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
                // restSettingsFlow rides the context combine so a Setup edit (master
                // toggle or a duration) republishes immediately — the watch never
                // counts a stale rest for longer than one sync hop.
                val context = combine(
                    settings.unitFlow,
                    settings.restSettingsFlow,
                    cardioAckLog.acksFlow,
                    editAckLog.processedEditsFlow,
                ) { unit, rest, cardio, edits ->
                    Context(unit, rest, cardio, edits)
                }
                plans.programFor(plan.id).map { it.dayToShow(dayId)?.id }.distinctUntilChanged().flatMapLatest resolved@ { resolvedId ->
                    if (resolvedId == null) return@resolved flowOf(null)
                    combine(
                        repo.observeDay(plan.id, resolvedId), repo.openRunFlow, context,
                        combine(historyRepository.cardioSessionsFlow, historyRepository.cardioSetResultsFlow, cardioDraft.runs) { sessions, sets, run ->
                            Triple(sessions, sets, run)
                        }, today,
                    ) { performedDay, open, ctx, cardioHistory, civilDate ->
                        val (sessions, sets, draft) = cardioHistory
                        val loggedCardio = sessions.firstOrNull { session ->
                            session.planId == plan.id && session.dayId == resolvedId &&
                                Instant.ofEpochMilli(session.completedAt).atZone(ZoneId.systemDefault()).toLocalDate() == civilDate
                        }
                        WatchSnapshotBuilder.build(
                            day = performedDay,
                            unit = ctx.unit,
                            revision = 0L,
                            restSettings = ctx.restSettings,
                            loggedCardio = loggedCardio,
                            cardioAckStamp = ctx.cardioAck.highest,
                            cardioAckedStamps = ctx.cardioAck.recent,
                            planName = plan.name,
                            runStartedAt = open?.takeIf { !it.draft && performedDay.tracks.runOpen && it.id == performedDay.tracks.runId }?.startedAt,
                            processedEditStamps = ctx.processedEdits,
                            liftGoal = plan.planSettings.defaultsFor,
                            cardioOpening = performedDay.day?.let { programDay ->
                                draft?.takeIf {
                                    loggedCardio == null && it.planId == plan.id && it.dayId == resolvedId &&
                                        it.startedAt == null && it.results.isEmpty() &&
                                        programDay.cardioToRun?.let(CardioBlockJson::encode) == it.sourceBlockJson
                                }?.block ?: DayScreenBuilder.cardioOpening(programDay, plan, sessions, sets, clock.millis())
                            },
                        )
                    }
                }
            }
        })
    }

    private data class Context(
        val unit: WeightUnit,
        val restSettings: RestSettings,
        val cardioAck: CardioAcks,
        val processedEdits: List<Long>,
    )
}

/** Shares the SavedStateHandle's run flow with glance surfaces, without another copy of the draft. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CardioDraftSource @Inject constructor() {
    private val savedRun = MutableStateFlow<StateFlow<CardioRun?>?>(null)
    val runs: Flow<CardioRun?> = savedRun.flatMapLatest { it ?: flowOf(null) }

    fun bind(run: StateFlow<CardioRun?>) {
        savedRun.value = run
    }
}
