package cloud.trotter.log.strength.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.LoggedSlot
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.di.CivilDay
import cloud.trotter.log.strength.domain.glance.GlanceLines
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Today screen ViewModel (#121, MVVM/UDF). Holds no working truth of its own:
 * every render value derives from repository flows, and every formatting
 * decision is delegated to the pure [TodayScreenBuilder] or to
 * [GlanceLines] — Today has no state to save, because it has no intents to
 * save it from. Read-only by design: it never seeds a log and never writes one.
 *
 * Seeding stays [cloud.trotter.log.strength.ui.day.DayViewModel]'s job, but the
 * *rules* are `:domain`'s, so Today can run them without writing anything: a day
 * with no log rows yet is previewed at [seededRowCount], the count the day
 * screen will show the moment it is opened. A preview that promised a different
 * number than the workout it opens would be a lie, and this screen exists to
 * tell the truth about what is next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repo: TrackerRepository,
    @CivilDay private val today: Flow<LocalDate>,
) : ViewModel() {

    private val contextFlow =
        combine(
            repo.programFlow,
            repo.suggestedDayFlow,
            repo.catalogFlow,
            repo.unitFlow,
            repo.configFlow,
        ) { program, suggested, catalog, unit, cfg ->
            val dayId = suggested?.takeIf { id -> program.days.any { it.id == id } }
                ?: program.days.firstOrNull()?.id
            TodayContext(program, dayId, catalog, unit, cfg)
        }

    val uiState: StateFlow<TodayUiState> = contextFlow.flatMapLatest { ctx ->
        val dayId = ctx.dayId
        if (dayId == null) {
            // [contextFlow] picks the day from the same program emission, so a
            // null day here means the program has no days — the answer, not a
            // pause. Loading is the state before this flow emits at all, which
            // is [uiState]'s initial value below (#127).
            flowOf(TodayUiState())
        } else {
            combine(
                repo.daySlotsFlow(dayId),
                repo.logFlow(dayId, today),
                repo.sessionSummariesFlow,
                repo.topSetHistoryFlow,
            ) { slots, logs, sessions, topSets ->
                build(ctx, dayId, slots, logs, sessions, topSets)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodayUiState(loading = true))

    private fun build(
        ctx: TodayContext,
        dayId: String,
        slots: List<ProgramSlot>,
        logs: List<LoggedSlot>,
        sessions: List<SessionSummaryRow>,
        topSets: List<TopSetRow>,
    ): TodayUiState {
        val day = ctx.program.days.first { it.id == dayId }
        val dayIndex = ctx.program.days.indexOfFirst { it.id == dayId }
        // Rounds, the same count the widget, the watch and the session receipt
        // show — see [Slot.isRound] for why a superset partner isn't one.
        val mainLogs = logs.filter { Slot.isRound(it.slot) }.associateBy { it.programExerciseId }
        val rowsBySlot = slots.associate { slot ->
            slot.programExerciseId to mainLogs[slot.programExerciseId]?.sets
        }
        val countBySlot = slots.associate { slot ->
            slot.programExerciseId to (rowsBySlot[slot.programExerciseId]?.size ?: seededRowCount(slot, ctx))
        }
        val totalSets = countBySlot.values.sum()
        val doneSets = slots.sumOf { slot -> rowsBySlot[slot.programExerciseId]?.count { it.done } ?: 0 }

        return TodayUiState(
            hasProgram = true,
            dayId = dayId,
            dayIndex = dayIndex,
            dayLine = GlanceLines.dayLine(dayId, day.title),
            overline = TodayScreenBuilder.overline(doneSets, totalSets),
            emphasisLine = day.emphasisLine,
            statLine = GlanceLines.statLine(slots.size, doneSets, totalSets),
            lifts = slots.map { slot ->
                TodayLift(
                    name = ctx.catalog.find(slot.exercise.exerciseId)?.name ?: slot.exercise.exerciseId,
                    setCount = countBySlot.getValue(slot.programExerciseId),
                    isMain = slot.exercise.isMain,
                )
            },
            cardio = day.cardio?.let { TodayCardio(it.label, it.detail, it.hard) },
            actionLabel = TodayScreenBuilder.actionLabel(dayId, doneSets, totalSets),
            lastSession = TodayScreenBuilder.lastSessionLine(sessions, topSets, ctx.catalog, ctx.unit),
            rotation = TodayScreenBuilder.rotationMarks(ctx.program.days.map { it.id }, dayId),
        )
    }

    /**
     * How many rows a slot the lifter has never opened *will* have, run through
     * the same `:domain` seeding the day screen writes on first visit
     * ([SetSeeder.seed] off [GoalCalculator.targetFor]) rather than the slot's
     * `targetSets`. Those two disagree for every weighted main — a main with a
     * target of 3 seeds four ramps, a TOP and a back-off — so reading
     * `targetSets` here would promise a 3-set day and open a 6-set one.
     *
     * The unresolved-id fallback lives in [DayScreenBuilder.previewMainSets], so
     * Today and snapshot/glance surfaces cannot drift on this count.
     */
    private fun seededRowCount(slot: ProgramSlot, ctx: TodayContext): Int =
        DayScreenBuilder.previewMainSets(slot, ctx.cfg, ctx.catalog).size

    private data class TodayContext(
        val program: Program,
        val dayId: String?,
        val catalog: ExerciseCatalog,
        val unit: WeightUnit,
        val cfg: LifterConfig,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
