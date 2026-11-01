package cloud.trotter.log.strength.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.units.WeightUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import cloud.trotter.log.strength.rest.RestRuntime
import cloud.trotter.log.strength.rest.NoOpRestRuntime

/**
 * Setup screen ViewModel (spec §8.4). Unlike the wizard, there is no draft:
 * every field writes straight through [TrackerRepository] and commits
 * immediately (data principle — no unsaved truth), so [uiState] is a pure
 * projection of `configFlow`/`unitFlow`/`wizardAnswersFlow`
 * via the pure [SetupStateBuilder]. None of these writes touch the program or
 * live logs, so existing ACTUAL history never moves when GOAL config changes
 * (spec §8.4's GOAL-vs-ACTUAL rule) — the day screen's GOAL block simply
 * re-derives from the same `configFlow` this screen writes to.
 *
 * Each setter is a read-modify-write over its store (config),
 * so it is serialized through [mutationLock] — the same lost-update guard
 * [cloud.trotter.log.strength.ui.day.DayViewModel] uses for log edits —
 * to stop two rapid stepper taps from both reading the same stale snapshot.
 * The rest-timer setters skip the lock: like [setUnit], each is a direct
 * single-key write, not a read-modify-write over a composite value.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val restRuntime: RestRuntime = NoOpRestRuntime,
) : ViewModel() {

    private val mutationLock = Mutex()

    private val trainingBasics = combine(
        repo.configFlow,
        repo.unitFlow,
        repo.wizardAnswersFlow,
        repo.restSettingsFlow,
    ) { cfg, unit, answers, restSettings ->
        SetupStateBuilder.buildUiState(cfg, unit, answers, restSettings)
    }
    private val trainingState = combine(trainingBasics, repo.phoneRestTimerEnabledFlow) { state, phoneEnabled ->
        state.copy(phoneRestTimerEnabled = phoneEnabled)
    }

    val uiState: StateFlow<SetupUiState> = combine(trainingState, repo.themePreferenceFlow, repo.keepScreenOnFlow) { state, theme, keepOn ->
        state.copy(themePreference = theme, keepScreenOn = keepOn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SetupUiState())

    fun setBodyweight(displayValue: Double) = mutateConfig { cfg, unit ->
        cfg.copy(bodyweightLb = SetupStateBuilder.bodyweightLb(displayValue, unit))
    }

    fun setAge(age: Int) = mutateConfig { cfg, _ -> cfg.copy(age = age.coerceAtLeast(1)) }

    fun setLevel(level: ExperienceLevel) = mutateConfig { cfg, _ -> cfg.copy(level = level) }

    fun setEmphasis(emphasis: GoalEmphasis) = mutateConfig { cfg, _ -> cfg.copy(emphasis = emphasis) }

    fun setUnit(unit: WeightUnit) {
        viewModelScope.launch { mutationLock.withLock { repo.setUnit(unit) } }
    }

    fun setThemePreference(theme: cloud.trotter.log.strength.domain.theme.ThemePreference) {
        viewModelScope.launch { repo.setThemePreference(theme) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { repo.setKeepScreenOn(enabled) }
    }

    fun setRestTimerEnabled(enabled: Boolean) {
        viewModelScope.launch { repo.setRestTimerEnabled(enabled) }
    }
    fun setPhoneRestTimerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repo.setPhoneRestTimerEnabled(enabled)
            if (!enabled) restRuntime.cancel()
        }
    }

    fun setRestOverride(category: RestCategory, seconds: Int) {
        viewModelScope.launch { repo.setRestOverride(category, seconds) }
    }

    fun clearRestOverrides() {
        viewModelScope.launch { repo.clearRestOverrides() }
    }

    private fun mutateConfig(transform: (LifterConfig, WeightUnit) -> LifterConfig) {
        viewModelScope.launch {
            mutationLock.withLock {
                val cfg = repo.configFlow.first()
                val unit = repo.unitFlow.first()
                repo.setConfig(transform(cfg, unit))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
