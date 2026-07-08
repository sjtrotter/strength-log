package io.github.sjtrotter.strengthlog.ui.day

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.sjtrotter.strengthlog.data.ProgramSlot
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.generator.Rotation
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.seeding.SetEditor
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Day screen ViewModel (spec §8.2, MVVM/UDF). Holds no working truth of its own:
 * every render value derives from Room/DataStore flows, and every mutation writes
 * straight back through [TrackerRepository]. Ephemeral UI state — the view-day
 * override, keep-screen-on, and the manual collapse overrides — lives in
 * [SavedStateHandle] per PLAN.md A6, so rotation and process death can't lose it;
 * collapse overrides still reset when a session advances (spec §8.2).
 *
 * Log mutations are read-modify-write over a whole set track, so they are
 * serialized through [mutationLock] — without it two rapid intents on one slot
 * (tick set 1, tick set 2) could each read the same stored list and the second
 * write would silently drop the first.
 *
 * The §8.2 decision logic lives in the pure [DayScreenBuilder]; this class is the
 * flow/write wiring around it. All cascade/edit math comes from `:domain`
 * ([SetEditor]) — never reimplemented here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DayViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val viewDayOverride: StateFlow<String?> = savedState.getStateFlow(KEY_VIEW_DAY, null)
    private val keepScreenOn: StateFlow<Boolean> = savedState.getStateFlow(KEY_KEEP_ON, false)
    private val manualCollapse: StateFlow<Map<Long, Boolean>> =
        savedState.getStateFlow(KEY_COLLAPSE, emptyMap())

    /** Serializes all read-modify-write log mutations (see class doc). */
    private val mutationLock = Mutex()

    /** The day actually shown: a valid override, else the suggested day, else the first. */
    private val effectiveDayId: StateFlow<String?> =
        combine(repo.programFlow, repo.suggestedDayFlow, viewDayOverride) { program, suggested, override ->
            resolveDay(program, suggested, override)
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val contextFlow =
        combine(
            repo.programFlow,
            repo.suggestedDayFlow,
            effectiveDayId,
            keepScreenOn,
            ::PartialContext,
        ).let { partial ->
            combine(partial, repo.configFlow, repo.unitFlow, repo.catalogFlow) { p, cfg, unit, catalog ->
                DayContext(p.program, p.suggested, p.dayId, cfg, unit, catalog, p.keepOn)
            }
        }

    val uiState: StateFlow<DayUiState> = contextFlow.flatMapLatest { ctx ->
        val dayId = ctx.dayId
        if (dayId == null) {
            flowOf(DayUiState(hasProgram = false, keepScreenOn = ctx.keepOn))
        } else {
            combine(repo.daySlotsFlow(dayId), repo.logFlow(dayId), manualCollapse) { slots, logs, collapse ->
                buildState(ctx, dayId, slots, logs.associateBy { it.programExerciseId to it.slot }, collapse)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DayUiState())

    init {
        seedMissingLogs()
    }

    // --- user intents --------------------------------------------------------

    fun selectDay(dayId: String) {
        savedState[KEY_VIEW_DAY] = dayId
    }

    fun changeWeight(programExerciseId: Long, slot: String, index: Int, newDisplayWeight: Double) {
        val day = currentDay() ?: return
        mutate {
            val unit = repo.unitFlow.first()
            val sets = trackFor(day, programExerciseId, slot)
            if (index !in sets.indices) return@mutate
            val updated = SetEditor.editWeight(sets, index, unit.toLb(newDisplayWeight))
            repo.updateSets(day, programExerciseId, slot, updated)
        }
    }

    fun changeReps(programExerciseId: Long, slot: String, index: Int, newReps: Int) {
        val day = currentDay() ?: return
        mutate {
            val sets = trackFor(day, programExerciseId, slot)
            if (index !in sets.indices) return@mutate
            repo.updateSets(day, programExerciseId, slot, SetEditor.editReps(sets, index, newReps))
        }
    }

    fun toggleDone(programExerciseId: Long, index: Int, checked: Boolean, isSuperset: Boolean) {
        val day = currentDay() ?: return
        mutate {
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            // A missing partner track (never seeded) must stay missing — writing an
            // empty SS row would mark it as seeded forever. Same rule as addSet.
            val partner = if (isSuperset) {
                trackFor(day, programExerciseId, Slot.SS).takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val (newMain, newPartner) = DayScreenBuilder.applyRoundTick(main, partner, index, checked)
            if (newPartner != null) {
                repo.updateSetsPaired(day, programExerciseId, newMain, newPartner)
            } else {
                repo.updateSets(day, programExerciseId, Slot.MAIN, newMain)
            }
        }
    }

    fun addSet(programExerciseId: Long, isSuperset: Boolean) {
        val day = currentDay() ?: return
        mutate {
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            // An unseeded track has no last row to copy — nothing to add yet.
            if (main.isEmpty()) return@mutate
            val partner = if (isSuperset) trackFor(day, programExerciseId, Slot.SS) else emptyList()
            if (partner.isNotEmpty()) {
                val (newMain, newPartner) = SetEditor.addSetPaired(main, partner)
                repo.updateSetsPaired(day, programExerciseId, newMain, newPartner)
            } else {
                // No partner track exists (e.g. its catalog entry is unknown, so it
                // never seeded) — there is nothing to keep aligned; plain add.
                repo.updateSets(day, programExerciseId, Slot.MAIN, SetEditor.addSet(main))
            }
        }
    }

    fun removeSet(programExerciseId: Long, index: Int, isSuperset: Boolean) {
        val day = currentDay() ?: return
        mutate {
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            if (index !in main.indices) return@mutate
            val partner = if (isSuperset) trackFor(day, programExerciseId, Slot.SS) else emptyList()
            if (partner.isNotEmpty()) {
                val (newMain, newPartner) = SetEditor.removeSetPaired(main, partner, index)
                repo.updateSetsPaired(day, programExerciseId, newMain, newPartner)
            } else {
                repo.updateSets(day, programExerciseId, Slot.MAIN, SetEditor.removeSet(main, index))
            }
        }
    }

    fun toggleCollapse(programExerciseId: Long) {
        val card = uiState.value.exercises.firstOrNull { it.programExerciseId == programExerciseId } ?: return
        savedState[KEY_COLLAPSE] = manualCollapse.value + (programExerciseId to !card.collapsed)
    }

    fun setKeepScreenOn(on: Boolean) {
        savedState[KEY_KEEP_ON] = on
    }

    fun clearChecks() {
        val day = currentDay() ?: return
        // Under the lock: a mutation that read its track before the clear must not
        // write stale done-flags back after it.
        mutate { repo.clearChecks(day) }
    }

    /** DONE — advance: record the session (A1), reset collapse, follow the new suggested day. */
    fun completeDay() {
        val day = currentDay() ?: return
        mutate {
            repo.advanceDay(day)
            savedState[KEY_COLLAPSE] = emptyMap<Long, Boolean>()
            savedState[KEY_VIEW_DAY] = null
        }
    }

    // --- seeding & bootstrap -------------------------------------------------

    /** Seeds each day's logs from GOAL exactly once (M2 rule: seeding is the VM's job). */
    private fun seedMissingLogs() {
        viewModelScope.launch {
            effectiveDayId
                .flatMapLatest { day ->
                    if (day == null) flowOf(null) else repo.daySlotsFlow(day).map { day to it }
                }
                .distinctUntilChanged()
                .collect { pair -> pair?.let { (day, slots) -> ensureSeeded(day, slots) } }
        }
    }

    private suspend fun ensureSeeded(dayId: String, slots: List<ProgramSlot>) = mutationLock.withLock {
        val existing = repo.logFlow(dayId).first().map { it.programExerciseId to it.slot }.toSet()
        val cfg = repo.configFlow.first()
        val catalog = repo.catalogFlow.first()
        DayScreenBuilder.seedPlan(slots, existing, cfg, catalog).forEach { write ->
            repo.updateSets(dayId, write.programExerciseId, write.slot, write.sets)
        }
    }

    // --- helpers -------------------------------------------------------------

    /** Launches a log mutation with its read-modify-write held under [mutationLock]. */
    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch { mutationLock.withLock { block() } }
    }

    private fun currentDay(): String? = effectiveDayId.value

    private suspend fun trackFor(dayId: String, programExerciseId: Long, slot: String): List<LoggedSet> =
        repo.logFlow(dayId).first()
            .firstOrNull { it.programExerciseId == programExerciseId && it.slot == slot }
            ?.sets ?: emptyList()

    private fun resolveDay(program: Program, suggested: String?, override: String?): String? {
        val ids = program.days.map { it.id }
        return override?.takeIf { it in ids } ?: suggested?.takeIf { it in ids } ?: ids.firstOrNull()
    }

    private fun buildState(
        ctx: DayContext,
        dayId: String,
        slots: List<ProgramSlot>,
        logsByKey: Map<Pair<Long, String>, io.github.sjtrotter.strengthlog.data.LoggedSlot>,
        collapse: Map<Long, Boolean>,
    ): DayUiState {
        val program = ctx.program
        val day = program.days.firstOrNull { it.id == dayId }
            ?: return DayUiState(hasProgram = false, keepScreenOn = ctx.keepOn)
        val dayIndex = program.days.indexOfFirst { it.id == dayId }
        return DayUiState(
            hasProgram = true,
            tabs = program.days.mapIndexed { i, d ->
                DayTab(d.id, i, isSuggested = d.id == ctx.suggested, isSelected = d.id == dayId)
            },
            viewDayId = dayId,
            dayIndex = dayIndex,
            dayTitle = day.title,
            emphasisLine = day.emphasisLine,
            unit = ctx.unit,
            suggestedDayId = ctx.suggested,
            nextDayId = Rotation.next(program, dayId),
            exercises = slots.map { buildCard(it, logsByKey, ctx.cfg, ctx.unit, ctx.catalog, collapse) },
            cardio = day.cardio,
            keepScreenOn = ctx.keepOn,
        )
    }

    private fun buildCard(
        slot: ProgramSlot,
        logsByKey: Map<Pair<Long, String>, io.github.sjtrotter.strengthlog.data.LoggedSlot>,
        cfg: LifterConfig,
        unit: WeightUnit,
        catalog: ExerciseCatalog,
        collapse: Map<Long, Boolean>,
    ): ExerciseCardState {
        val pe = slot.exercise
        val id = slot.programExerciseId
        val entry = catalog.find(pe.exerciseId)
        val name = entry?.name ?: pe.exerciseId
        val goalLb = entry?.let { GoalCalculator.goalFor(it, cfg) } ?: 0.0
        val main = logsByKey[id to Slot.MAIN]?.sets ?: emptyList()
        val partnerEntry = pe.superset?.let { catalog.find(it.exerciseId) }
        val partnerSets = pe.superset?.let { logsByKey[id to Slot.SS]?.sets }

        val labels = DayScreenBuilder.kindLabels(main)
        val rows = main.mapIndexed { i, s ->
            SetRowState(
                index = i,
                kindLabel = labels.getOrElse(i) { "" },
                isTop = s.kind == SetKind.TOP,
                weightDisplay = unit.fromLb(s.weightLb),
                reps = s.reps,
                done = s.done,
                partner = partnerSets?.getOrNull(i)?.let {
                    PartnerRowState(weightDisplay = unit.fromLb(it.weightLb), reps = it.reps)
                },
            )
        }
        val goalDisplay = DayScreenBuilder.goalDisplay(goalLb, unit)
        return ExerciseCardState(
            programExerciseId = id,
            title = if (pe.superset != null) {
                "SS: $name + ${partnerEntry?.name ?: pe.superset!!.exerciseId}"
            } else {
                name
            },
            isMain = pe.isMain,
            isSuperset = pe.superset != null,
            hasWarmupHint = pe.hasWarmupHint,
            goalDisplay = goalDisplay,
            perHand = entry?.perHand == true,
            partnerGoalDisplay = partnerEntry?.let {
                DayScreenBuilder.goalDisplay(GoalCalculator.goalFor(it, cfg), unit)
            },
            partnerPerHand = partnerEntry?.perHand == true,
            allDone = DayScreenBuilder.allDone(main),
            collapsed = DayScreenBuilder.collapsed(main, collapse[id]),
            collapsedSummary = DayScreenBuilder.collapsedSummary(main, partnerSets, goalDisplay, unit),
            rows = rows,
        )
    }

    private data class PartialContext(
        val program: Program,
        val suggested: String?,
        val dayId: String?,
        val keepOn: Boolean,
    )

    private data class DayContext(
        val program: Program,
        val suggested: String?,
        val dayId: String?,
        val cfg: LifterConfig,
        val unit: WeightUnit,
        val catalog: ExerciseCatalog,
        val keepOn: Boolean,
    )

    private companion object {
        const val KEY_VIEW_DAY = "day_view_override"
        const val KEY_KEEP_ON = "day_keep_screen_on"
        const val KEY_COLLAPSE = "day_collapse_overrides"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
