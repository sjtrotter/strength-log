package cloud.trotter.log.strength.ui.wizard

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.transfer.backup.BackupError
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.ui.backup.TransferErrorMessages
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup wizard ViewModel (spec §6.1, PLAN.md A4). Every field of the
 * in-progress [WizardAnswers] draft lives in [SavedStateHandle] as an
 * individual primitive — the same shape [cloud.trotter.log.strength.data.prefs.SettingsStore]
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
 *
 * [Restore from backup][restoreFromBackup] is the other way out of the wizard,
 * offered on the first step of a genuine first run only — see [Keys.FIRST_RUN].
 */
@HiltViewModel
class WizardViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val backupService: BackupService,
) : ViewModel() {

    private object Keys {
        const val INITIALIZED = "wizard_initialized"

        /** Whether this wizard was entered with nothing on the device yet
         *  (latched from `wizardComplete` in [init], never re-read). It gates
         *  the restore-from-backup entry: on a Setup re-run the device already
         *  holds a program and a history, and Data/Backup owns import there
         *  behind a confirm-overwrite dialog — a second, unguarded destructive
         *  path off a setup screen is exactly the accident worth designing out. */
        const val FIRST_RUN = "wizard_first_run"
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

    private val firstRun: StateFlow<Boolean> = savedState.getStateFlow(Keys.FIRST_RUN, false)

    /** In-flight/failed state of a restore. Not in [SavedStateHandle] on purpose:
     *  it is progress, not truth — the import is one atomic all-or-nothing call,
     *  so process death mid-restore leaves the device either untouched or fully
     *  restored, and the user simply picks the file again. */
    private val restoreProgress = MutableStateFlow(RestoreProgress())

    private data class RestoreProgress(val inFlight: Boolean = false, val error: String? = null)

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
        combine(stepIndex, answersFlow, isComplete, firstRun, restoreProgress) { step, answers, complete, offered, progress ->
            WizardStateBuilder.buildUiState(
                stepIndex = step,
                answers = answers,
                isComplete = complete,
                restore = WizardRestoreState(offered = offered, inFlight = progress.inFlight, error = progress.error),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WizardUiState())

    init {
        if (savedState.get<Boolean>(Keys.INITIALIZED) != true) {
            viewModelScope.launch {
                applyAnswers(repo.wizardAnswersFlow.first())
                savedState[Keys.FIRST_RUN] = !repo.wizardCompleteFlow.first()
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

    // --- restore from backup (first run only) ----------------------------------

    /**
     * Restores the picked backup instead of answering the wizard. No confirm
     * dialog: this is only reachable on a first run (see [Keys.FIRST_RUN]), so
     * there is nothing on the device to overwrite. [BackupService] validates the
     * whole file before it writes a byte, so a bad file leaves a fresh install
     * fresh and only puts a message on the screen.
     *
     * Where it lands is the *restored document's* call, not ours. A backup taken
     * mid-first-run carries `wizardComplete = false` and no generated program —
     * leaving for the day screen there would strand it on "Preparing your
     * program…", the same trap [finish]'s write order avoids. So on that one
     * document we stay in the wizard, re-seeded with whatever answers the backup
     * did carry, and the lifter finishes setup from there.
     */
    fun restoreFromBackup(uri: Uri) {
        if (!firstRun.value || restoreProgress.value.inFlight) return
        restoreProgress.value = RestoreProgress(inFlight = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { backupService.importFrom(it) }
                        ?: throw IOException("no input stream for $uri")
                }
                applyAnswers(repo.wizardAnswersFlow.first())
                if (repo.wizardCompleteFlow.first()) {
                    isComplete.value = true
                } else {
                    restoreProgress.value = RestoreProgress()
                }
            } catch (e: BackupError) {
                restoreProgress.value = RestoreProgress(error = TransferErrorMessages.of(e))
            } catch (e: IOException) {
                restoreProgress.value = RestoreProgress(error = "Couldn't access that file: ${e.message}")
            } catch (e: SecurityException) {
                // A revoked/expired SAF grant surfaces here, not as a crash.
                restoreProgress.value = RestoreProgress(error = "No permission to access that file anymore.")
            }
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
