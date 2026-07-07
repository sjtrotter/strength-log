package io.github.sjtrotter.strengthlog.ui.day

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.sjtrotter.strengthlog.data.ProgramSlot
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.Rotation
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.seeding.SetEditor
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * Day screen ViewModel (spec §8.2, MVVM/UDF). Holds no working truth of its own:
 * every render value derives from Room/DataStore flows, and every mutation writes
 * straight back through [TrackerRepository] (data principle — nothing that must
 * survive process death lives in a bare field). The only in-VM state is the
 * ephemeral view-day override and keep-screen-on (in [SavedStateHandle], so a
 * rotation keeps them) and the manual collapse overrides (in-memory, reset on
 * advance per spec §8.2).
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
    private val manualCollapse = MutableStateFlow<Map<Long, Boolean>>(emptyMap())

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
        bootstrapProgramIfEmpty()
        seedMissingLogs()
    }

    // --- user intents --------------------------------------------------------

    fun selectDay(dayId: String) {
        savedState[KEY_VIEW_DAY] = dayId
    }

    fun changeWeight(programExerciseId: Long, slot: String, index: Int, newDisplayWeight: Double) {
        val day = currentDay() ?: return
        viewModelScope.launch {
            val unit = repo.unitFlow.first()
            val sets = trackFor(day, programExerciseId, slot)
            if (index !in sets.indices) return@launch
            val updated = SetEditor.editWeight(sets, index, unit.toLb(newDisplayWeight))
            repo.updateSets(day, programExerciseId, slot, updated)
        }
    }

    fun changeReps(programExerciseId: Long, slot: String, index: Int, newReps: Int) {
        val day = currentDay() ?: return
        viewModelScope.launch {
            val sets = trackFor(day, programExerciseId, slot)
            if (index !in sets.indices) return@launch
            repo.updateSets(day, programExerciseId, slot, SetEditor.editReps(sets, index, newReps))
        }
    }

    fun toggleDone(programExerciseId: Long, index: Int, checked: Boolean, isSuperset: Boolean) {
        val day = currentDay() ?: return
        viewModelScope.launch {
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            val partner = if (isSuperset) trackFor(day, programExerciseId, Slot.SS) else null
            val (newMain, newPartner) = DayScreenBuilder.applyRoundTick(main, partner, index, checked)
            repo.updateSets(day, programExerciseId, Slot.MAIN, newMain)
            if (newPartner != null) repo.updateSets(day, programExerciseId, Slot.SS, newPartner)
        }
    }

    fun addSet(programExerciseId: Long, isSuperset: Boolean) {
        val day = currentDay() ?: return
        viewModelScope.launch {
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            if (isSuperset) {
                val partner = trackFor(day, programExerciseId, Slot.SS)
                val (newMain, newPartner) = SetEditor.addSetPaired(main, partner)
                repo.updateSets(day, programExerciseId, Slot.MAIN, newMain)
                repo.updateSets(day, programExerciseId, Slot.SS, newPartner)
            } else {
                repo.updateSets(day, programExerciseId, Slot.MAIN, SetEditor.addSet(main))
            }
        }
    }

    fun removeSet(programExerciseId: Long, index: Int, isSuperset: Boolean) {
        val day = currentDay() ?: return
        viewModelScope.launch {
            val main = trackFor(day, programExerciseId, Slot.MAIN)
            if (isSuperset) {
                val partner = trackFor(day, programExerciseId, Slot.SS)
                val (newMain, newPartner) = SetEditor.removeSetPaired(main, partner, index)
                repo.updateSets(day, programExerciseId, Slot.MAIN, newMain)
                repo.updateSets(day, programExerciseId, Slot.SS, newPartner)
            } else {
                repo.updateSets(day, programExerciseId, Slot.MAIN, SetEditor.removeSet(main, index))
            }
        }
    }

    fun toggleCollapse(programExerciseId: Long) {
        val card = uiState.value.exercises.firstOrNull { it.programExerciseId == programExerciseId } ?: return
        manualCollapse.value = manualCollapse.value + (programExerciseId to !card.collapsed)
    }

    fun setKeepScreenOn(on: Boolean) {
        savedState[KEY_KEEP_ON] = on
    }

    fun clearChecks() {
        val day = currentDay() ?: return
        viewModelScope.launch { repo.clearChecks(day) }
    }

    /** DONE — advance: record the session (A1), reset collapse, follow the new suggested day. */
    fun completeDay() {
        val day = currentDay() ?: return
        viewModelScope.launch {
            repo.advanceDay(day)
            manualCollapse.value = emptyMap()
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

    private suspend fun ensureSeeded(dayId: String, slots: List<ProgramSlot>) {
        val existing = repo.logFlow(dayId).first().map { it.programExerciseId to it.slot }.toSet()
        val cfg = repo.configFlow.first()
        val catalog = repo.catalogFlow.first()
        DayScreenBuilder.seedPlan(slots, existing, cfg, catalog).forEach { write ->
            repo.updateSets(dayId, write.programExerciseId, write.slot, write.sets)
        }
    }

    /**
     * Temporary: until the setup wizard (#9) lands, generate the default 4-day
     * full-body program on first run so the day screen has something to show.
     */
    private fun bootstrapProgramIfEmpty() {
        viewModelScope.launch {
            if (repo.programFlow.first().days.isEmpty()) {
                val answers = WizardAnswers()
                repo.setWizardAnswers(answers)
                repo.replaceProgram(ProgramGenerator.generate(answers).program)
            }
        }
    }

    // --- helpers -------------------------------------------------------------

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
            nextDayId = if (program.days.isNotEmpty()) Rotation.next(program, dayId) else null,
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
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
