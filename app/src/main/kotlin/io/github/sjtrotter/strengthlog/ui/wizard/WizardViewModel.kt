package io.github.sjtrotter.strengthlog.ui.wizard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Setup wizard ViewModel (spec §6.1, PLAN.md A4). Every field of the
 * in-progress [WizardAnswers] draft lives in [SavedStateHandle] as an
 * individual primitive — the same shape [io.github.sjtrotter.strengthlog.data.prefs.SettingsStore]
 * persists it in, so rotation/process death mid-wizard loses nothing (data
 * principle: no unsaved truth in a bare ViewModel field).
 *
 * [Finish][finish] is the only program creator left in the app (D3): it
 * persists the wizard answers and `wizardComplete`, generates via
 * [ProgramGenerator], and replaces the program through
 * [TrackerRepository.replaceProgram] — the `:data` surface the day-edit
 * sheet's "reset day to template" already relies on.
 *
 * Re-run entry (from Setup, #12) reuses this same route/ViewModel: [init]
 * seeds the draft from whatever is already stored, which is the spec default
 * on first run (an empty DataStore reads back [WizardAnswers] defaults) and
 * the lifter's last answers on a re-run.
 */
@HiltViewModel
class WizardViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private object Keys {
        const val INITIALIZED = "wizard_initialized"
        const val STEP = "wizard_step"
        const val DAYS = "wizard_days"
        const val SPLIT = "wizard_split"
        const val ANCHOR_SCHEME = "wizard_anchor_scheme"
        const val DEADLIFT = "wizard_deadlift"
        const val CARDIO_MODE = "wizard_cardio_mode"
        const val CARDIO_PLACEMENT = "wizard_cardio_placement"
        const val FIVE_K = "wizard_five_k"
        const val BODYWEIGHT = "wizard_bodyweight"
        const val AGE = "wizard_age"
        const val LEVEL = "wizard_level"
        const val EMPHASIS = "wizard_emphasis"
        const val EQUIPMENT = "wizard_equipment"
    }

    private val defaults = WizardAnswers()

    private val stepIndex: StateFlow<Int> = savedState.getStateFlow(Keys.STEP, 0)
    private val daysPerWeek: StateFlow<Int> = savedState.getStateFlow(Keys.DAYS, defaults.daysPerWeek)
    private val split: StateFlow<String> = savedState.getStateFlow(Keys.SPLIT, defaults.split.name)
    private val anchorScheme: StateFlow<String> =
        savedState.getStateFlow(Keys.ANCHOR_SCHEME, defaults.anchorScheme.name)
    private val deadliftVariant: StateFlow<String> =
        savedState.getStateFlow(Keys.DEADLIFT, defaults.deadliftVariant.name)
    private val cardioMode: StateFlow<String> = savedState.getStateFlow(Keys.CARDIO_MODE, defaults.cardio.mode.name)
    private val cardioPlacement: StateFlow<String> =
        savedState.getStateFlow(Keys.CARDIO_PLACEMENT, defaults.cardio.placement.name)
    private val fiveK: StateFlow<Boolean> = savedState.getStateFlow(Keys.FIVE_K, defaults.cardio.fiveKGoal)
    private val bodyweight: StateFlow<Int> = savedState.getStateFlow(Keys.BODYWEIGHT, defaults.config.bodyweightLb)
    private val age: StateFlow<Int> = savedState.getStateFlow(Keys.AGE, defaults.config.age)
    private val level: StateFlow<String> = savedState.getStateFlow(Keys.LEVEL, defaults.config.level.name)
    private val emphasis: StateFlow<String> = savedState.getStateFlow(Keys.EMPHASIS, defaults.config.emphasis.name)
    private val equipment: StateFlow<List<String>> =
        savedState.getStateFlow(Keys.EQUIPMENT, defaults.equipment.map { it.name })

    private val isComplete = MutableStateFlow(false)

    private data class SplitGroup(val days: Int, val split: SplitTemplate, val anchors: AnchorScheme, val deadlift: DeadliftVariant)
    private data class CardioGroup(val mode: CardioMode, val placement: CardioPlacement, val fiveK: Boolean)
    private data class AboutGroup(val bodyweight: Int, val age: Int, val level: ExperienceLevel, val emphasis: GoalEmphasis)

    private val splitGroup =
        combine(daysPerWeek, split, anchorScheme, deadliftVariant) { d, s, a, dl ->
            SplitGroup(d, enumOf(s, defaults.split), enumOf(a, defaults.anchorScheme), enumOf(dl, defaults.deadliftVariant))
        }
    private val cardioGroup =
        combine(cardioMode, cardioPlacement, fiveK) { m, p, k ->
            CardioGroup(enumOf(m, defaults.cardio.mode), enumOf(p, defaults.cardio.placement), k)
        }
    private val aboutGroup =
        combine(bodyweight, age, level, emphasis) { bw, a, l, e ->
            AboutGroup(bw, a, enumOf(l, defaults.config.level), enumOf(e, defaults.config.emphasis))
        }

    private fun assembleAnswers(sg: SplitGroup, cg: CardioGroup, ag: AboutGroup, equip: List<String>): WizardAnswers =
        WizardAnswers(
            daysPerWeek = sg.days,
            split = sg.split,
            anchorScheme = sg.anchors,
            deadliftVariant = sg.deadlift,
            cardio = CardioPrefs(mode = cg.mode, placement = cg.placement, fiveKGoal = cg.fiveK),
            config = LifterConfig(bodyweightLb = ag.bodyweight, age = ag.age, level = ag.level, emphasis = ag.emphasis),
            equipment = equip.mapNotNull { name -> Equipment.entries.firstOrNull { it.name == name } }.toSet(),
        )

    private val answersFlow: Flow<WizardAnswers> =
        combine(splitGroup, cardioGroup, aboutGroup, equipment) { sg, cg, ag, equip -> assembleAnswers(sg, cg, ag, equip) }

    /** Synchronous snapshot of the draft straight off the raw SavedStateHandle
     *  fields — used where a caller (namely [finish]) must not risk reading a
     *  not-yet-recomputed [uiState] (see the [onNext] doc). */
    private fun currentAnswers(): WizardAnswers = assembleAnswers(
        SplitGroup(
            daysPerWeek.value,
            enumOf(split.value, defaults.split),
            enumOf(anchorScheme.value, defaults.anchorScheme),
            enumOf(deadliftVariant.value, defaults.deadliftVariant),
        ),
        CardioGroup(enumOf(cardioMode.value, defaults.cardio.mode), enumOf(cardioPlacement.value, defaults.cardio.placement), fiveK.value),
        AboutGroup(bodyweight.value, age.value, enumOf(level.value, defaults.config.level), enumOf(emphasis.value, defaults.config.emphasis)),
        equipment.value,
    )

    val uiState: StateFlow<WizardUiState> =
        combine(stepIndex, answersFlow, isComplete) { step, answers, complete ->
            WizardStateBuilder.buildUiState(step, answers, complete)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WizardUiState())

    init {
        if (savedState.get<Boolean>(Keys.INITIALIZED) != true) {
            viewModelScope.launch {
                applyAnswers(repo.wizardAnswersFlow.first())
                savedState[Keys.INITIALIZED] = true
            }
        }
    }

    // --- navigation ------------------------------------------------------------

    // onNext/onBack read stepIndex.value (the raw SavedStateHandle-backed flow),
    // never uiState.value: uiState is combine()d asynchronously, so two calls
    // made back-to-back (before the dispatcher processes the first) would both
    // see the same stale step and collapse into a single advance — the same
    // lost-update shape DayViewModel's mutationLock guards against for set
    // edits. SavedStateHandle's own StateFlow has no such lag: a set is visible
    // to the very next read.

    fun onNext() {
        val current = stepIndex.value
        if (current >= WizardStep.entries.lastIndex) {
            finish()
        } else {
            savedState[Keys.STEP] = current + 1
        }
    }

    fun onBack() {
        val current = stepIndex.value
        if (current > 0) savedState[Keys.STEP] = current - 1
    }

    // --- field setters -----------------------------------------------------------

    fun setEmphasis(value: GoalEmphasis) {
        savedState[Keys.EMPHASIS] = value.name
    }

    fun setDaysPerWeek(value: Int) {
        val clamped = value.coerceIn(2, 6)
        savedState[Keys.DAYS] = clamped
        val currentSplit = enumOf(split.value, defaults.split)
        savedState[Keys.SPLIT] = WizardStateBuilder.splitForDays(currentSplit, clamped).name
    }

    fun setSplit(value: SplitTemplate) {
        savedState[Keys.SPLIT] = value.name
    }

    fun setAnchorScheme(value: AnchorScheme) {
        savedState[Keys.ANCHOR_SCHEME] = value.name
    }

    fun setDeadliftVariant(value: DeadliftVariant) {
        savedState[Keys.DEADLIFT] = value.name
    }

    fun setCardioMode(value: CardioMode) {
        savedState[Keys.CARDIO_MODE] = value.name
    }

    fun setCardioPlacement(value: CardioPlacement) {
        savedState[Keys.CARDIO_PLACEMENT] = value.name
    }

    fun setFiveK(value: Boolean) {
        savedState[Keys.FIVE_K] = value
    }

    fun setBodyweight(value: Int) {
        savedState[Keys.BODYWEIGHT] = value.coerceAtLeast(1)
    }

    fun setAge(value: Int) {
        savedState[Keys.AGE] = value.coerceAtLeast(1)
    }

    fun setLevel(value: ExperienceLevel) {
        savedState[Keys.LEVEL] = value.name
    }

    fun toggleEquipment(value: Equipment) {
        val current = equipment.value
        savedState[Keys.EQUIPMENT] = if (value.name in current) current - value.name else current + value.name
    }

    // --- finish --------------------------------------------------------------

    /**
     * Persists the answers, generates the program via `:domain`, replaces it,
     * and only then marks the wizard complete (spec §6, D3: the only program
     * creator).
     *
     * Write order is crash-safety, not cosmetics. `wizardComplete=true` is what
     * routes the app to the day screen (D1); if it were set before
     * [TrackerRepository.replaceProgram], a process death in the gap would
     * strand the app on an empty program — the day screen would show
     * "Preparing your program…" forever, and with the #10 bootstrap deleted
     * (D3) and Setup (#12) not yet built there is no in-app recovery. Setting
     * it last means a crash before it simply re-runs the wizard (the draft
     * survives in [SavedStateHandle]). Mirrors [TrackerRepository.importSnapshot]'s
     * own write-before-flag ordering.
     */
    private fun finish() {
        viewModelScope.launch {
            val answers = currentAnswers()
            repo.setWizardAnswers(answers)
            // Taking only .program drops GeneratedProgram.cardioDays: standalone
            // Cardio+Core day cards (spec §6.4, SEPARATE_DAYS/BOTH placements)
            // aren't modeled in :data or the day screen yet — tracked in
            // docs/briefs/m6-polish-ledger.md. Deliberately dropped whole here
            // rather than half-persisted.
            repo.replaceProgram(ProgramGenerator.generate(answers).program)
            repo.setWizardComplete(true)
            isComplete.value = true
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun applyAnswers(answers: WizardAnswers) {
        savedState[Keys.DAYS] = answers.daysPerWeek
        savedState[Keys.SPLIT] = answers.split.name
        savedState[Keys.ANCHOR_SCHEME] = answers.anchorScheme.name
        savedState[Keys.DEADLIFT] = answers.deadliftVariant.name
        savedState[Keys.CARDIO_MODE] = answers.cardio.mode.name
        savedState[Keys.CARDIO_PLACEMENT] = answers.cardio.placement.name
        savedState[Keys.FIVE_K] = answers.cardio.fiveKGoal
        savedState[Keys.BODYWEIGHT] = answers.config.bodyweightLb
        savedState[Keys.AGE] = answers.config.age
        savedState[Keys.LEVEL] = answers.config.level.name
        savedState[Keys.EMPHASIS] = answers.config.emphasis.name
        savedState[Keys.EQUIPMENT] = answers.equipment.map { it.name }
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
