package cloud.trotter.log.strength.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.rest.RestRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val restRuntime: RestRuntime = NoOpRestRuntime,
) : ViewModel() {

    private val preferences = combine(
        repo.unitFlow, repo.restSettingsFlow, repo.phoneRestTimerEnabledFlow,
    ) { unit, restSettings, phoneEnabled ->
        SetupStateBuilder.buildUiState(unit, restSettings, phoneEnabled)
    }

    val uiState: StateFlow<SetupUiState> = combine(preferences, repo.themePreferenceFlow, repo.keepScreenOnFlow) { state, theme, keepOn ->
        state.copy(themePreference = theme, keepScreenOn = keepOn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SetupUiState())

    fun setUnit(unit: WeightUnit) {
        viewModelScope.launch { repo.setUnit(unit) }
    }

    fun setThemePreference(theme: ThemePreference) {
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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
