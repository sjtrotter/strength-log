package cloud.trotter.log.strength.ui.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.ui.setup.SetupStateBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class YouViewModel @Inject constructor(
    private val settings: SettingsStore,
) : ViewModel() {
    // Absolute edits commit in order, including quick focus changes between fields.
    private val writeOrder = Mutex()

    val uiState = combine(settings.lifterProfileFlow, settings.unitFlow, ::YouUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), YouUiState())

    fun setBodyweight(displayValue: Double) {
        update {
            val unit = settings.unitFlow.first()
            settings.updateLifterProfile { it.copy(bodyweightLb = SetupStateBuilder.bodyweightLb(displayValue, unit)) }
        }
    }

    fun setAge(age: Int) {
        update { settings.updateLifterProfile { it.copy(age = age.coerceAtLeast(1)) } }
    }

    private fun update(write: suspend () -> Unit) {
        viewModelScope.launch { writeOrder.withLock { write() } }
    }
}
