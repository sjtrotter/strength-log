package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.TrackerRepository
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

/**
 * The one observed projection of *today* — the suggested day, its sets, their
 * done flags and the display unit — that every glance surface reads. The watch
 * publisher ([WearSyncPublisher]) mirrors it onto the Data Layer; the
 * home-screen widget ([cloud.trotter.log.strength.widget.TodayWidgetUpdater])
 * renders from it. Neither consumer re-derives "which day is today" or counts
 * sets of its own (glance-surfaces.md §4.2).
 *
 * Null means there is nothing glanceable: no program yet, or the suggested day
 * resolves to no real day.
 *
 * [WatchSnapshot.revision] is a placeholder 0 here — only the publisher stamps a
 * real revision, at publish time, so dedupe upstream compares content alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodaySnapshotSource(
    private val repo: TrackerRepository,
    @CivilDay private val today: Flow<LocalDate>,
    private val markers: AppliedEditMarkers,
) {

    val snapshots: Flow<WatchSnapshot?> =
        repo.suggestedDayFlow.flatMapLatest { dayId ->
            if (dayId == null) {
                flowOf(null)
            } else {
                val program = combine(
                    repo.programFlow,
                    repo.daySlotsFlow(dayId),
                    repo.logFlow(dayId, today),
                ) { program, slots, logs -> Triple(program, slots, logs) }
                // restSettingsFlow rides the context combine so a Setup edit (master
                // toggle or a duration) republishes immediately — the watch never
                // counts a stale rest for longer than one sync hop.
                val context = combine(
                    repo.configFlow,
                    repo.catalogFlow,
                    repo.unitFlow,
                    repo.restSettingsFlow,
                    repo.wizardAnswersFlow,
                ) { cfg, catalog, unit, rest, answers ->
                    Context(cfg, catalog, unit, rest, answers.equipment, answers.cardio.mode.name, answers.cardio.fiveKGoal)
                }
                combine(
                    program, context, repo.cardioSessionsFlow, today,
                    markers.lastAppliedFlow(CardioDeltaApplier.MARKER_KEY),
                ) { (prog, slots, logs), ctx, cardioHistory, civilDate, cardioAck ->
                    val loggedCardio = cardioHistory.firstOrNull { session ->
                        session.dayId == dayId &&
                            Instant.ofEpochMilli(session.completedAt).atZone(ZoneId.systemDefault()).toLocalDate() == civilDate
                    }
                    WatchSnapshotBuilder.build(
                        program = prog,
                        suggestedDayId = dayId,
                        slots = slots,
                        logs = logs,
                        cfg = ctx.cfg,
                        catalog = ctx.catalog,
                        unit = ctx.unit,
                        revision = 0L,
                        restSettings = ctx.restSettings,
                        equipment = ctx.equipment,
                        loggedCardio = loggedCardio,
                        cardioAckStamp = cardioAck,
                        cardioMode = ctx.cardioMode,
                        cardioFiveK = ctx.cardioFiveK,
                    )
                }
            }
        }

    /** The non-program half of a snapshot's inputs, combined separately so the
     *  program flows and the preference flows conflate independently. */
    private data class Context(
        val cfg: LifterConfig,
        val catalog: ExerciseCatalog,
        val unit: WeightUnit,
        val restSettings: RestSettings,
        val equipment: Set<Equipment>,
        val cardioMode: String,
        val cardioFiveK: Boolean,
    )
}
