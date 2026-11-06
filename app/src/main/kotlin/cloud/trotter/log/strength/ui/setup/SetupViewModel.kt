package cloud.trotter.log.strength.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.rest.PhoneRestController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val phoneRest: PhoneRestController,
) : ViewModel() {

    private val preferences = combine(
        settings.unitFlow, settings.restSettingsFlow, settings.phoneRestTimerEnabledFlow, settings.distanceUnitFlow,
    ) { unit, restSettings, phoneEnabled, distanceUnit ->
        SetupStateBuilder.buildUiState(unit, restSettings, phoneEnabled, distanceUnit = distanceUnit)
    }

    val uiState: StateFlow<SetupUiState> = combine(preferences, settings.themePreferenceFlow, settings.keepScreenOnFlow) { state, theme, keepOn ->
        state.copy(themePreference = theme, keepScreenOn = keepOn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SetupUiState())

    fun setUnit(unit: WeightUnit) {
        viewModelScope.launch { settings.setUnit(unit) }
    }

    fun setDistanceUnit(unit: DistanceUnit) {
        viewModelScope.launch { settings.setDistanceUnit(unit) }
    }

    fun setThemePreference(theme: ThemePreference) {
        viewModelScope.launch { settings.setThemePreference(theme) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settings.setKeepScreenOn(enabled) }
    }

    fun setRestTimerEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setRestTimerEnabled(enabled) }
    }
    fun setPhoneRestTimerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            phoneRest.locked {
                settings.setPhoneRestTimerEnabled(enabled)
                if (!enabled) skip()
            }
        }
    }

    fun setRestOverride(category: RestCategory, seconds: Int) {
        viewModelScope.launch { settings.setRestOverride(category, seconds) }
    }

    fun clearRestOverrides() {
        viewModelScope.launch { settings.clearRestOverrides() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
