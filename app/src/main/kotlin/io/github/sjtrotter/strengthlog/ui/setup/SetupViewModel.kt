package io.github.sjtrotter.strengthlog.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.standards.RestCategory
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Setup screen ViewModel (spec §8.4). Unlike the wizard, there is no draft:
 * every field writes straight through [TrackerRepository] and commits
 * immediately (data principle — no unsaved truth), so [uiState] is a pure
 * projection of `configFlow`/`cardioPrefsFlow`/`unitFlow`/`wizardAnswersFlow`
 * via the pure [SetupStateBuilder]. None of these writes touch the program or
 * live logs, so existing ACTUAL history never moves when GOAL config changes
 * (spec §8.4's GOAL-vs-ACTUAL rule) — the day screen's GOAL block simply
 * re-derives from the same `configFlow` this screen writes to.
 *
 * Each setter is a read-modify-write over its store (config or cardio prefs),
 * so it is serialized through [mutationLock] — the same lost-update guard
 * [io.github.sjtrotter.strengthlog.ui.day.DayViewModel] uses for log edits —
 * to stop two rapid stepper taps from both reading the same stale snapshot.
 * The rest-timer setters skip the lock: like [setUnit], each is a direct
 * single-key write, not a read-modify-write over a composite value.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(private val repo: TrackerRepository) : ViewModel() {

    private val mutationLock = Mutex()

    val uiState: StateFlow<SetupUiState> = combine(
        repo.configFlow,
        repo.cardioPrefsFlow,
        repo.unitFlow,
        repo.wizardAnswersFlow,
        repo.restSettingsFlow,
    ) { cfg, cardio, unit, answers, restSettings ->
        SetupStateBuilder.buildUiState(cfg, cardio, unit, answers, restSettings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SetupUiState())

    fun setBodyweight(displayValue: Double) = mutateConfig { cfg, unit ->
        cfg.copy(bodyweightLb = SetupStateBuilder.bodyweightLb(displayValue, unit))
    }

    fun setAge(age: Int) = mutateConfig { cfg, _ -> cfg.copy(age = age.coerceAtLeast(1)) }

    fun setLevel(level: ExperienceLevel) = mutateConfig { cfg, _ -> cfg.copy(level = level) }

    fun setEmphasis(emphasis: GoalEmphasis) = mutateConfig { cfg, _ -> cfg.copy(emphasis = emphasis) }

    fun setCardioMode(mode: CardioMode) = mutateCardio { cardio -> cardio.copy(mode = mode) }

    fun setCardioPlacement(placement: CardioPlacement) = mutateCardio { cardio -> cardio.copy(placement = placement) }

    fun setFiveK(fiveK: Boolean) = mutateCardio { cardio -> cardio.copy(fiveKGoal = fiveK) }

    fun setUnit(unit: WeightUnit) {
        viewModelScope.launch { mutationLock.withLock { repo.setUnit(unit) } }
    }

    fun setRestTimerEnabled(enabled: Boolean) {
        viewModelScope.launch { repo.setRestTimerEnabled(enabled) }
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

    private fun mutateCardio(transform: (CardioPrefs) -> CardioPrefs) {
        viewModelScope.launch {
            mutationLock.withLock {
                repo.setCardioPrefs(transform(repo.cardioPrefsFlow.first()))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
