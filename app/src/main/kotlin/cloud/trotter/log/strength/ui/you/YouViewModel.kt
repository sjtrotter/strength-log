package cloud.trotter.log.strength.ui.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.ExperienceLevel
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
    private val repo: PlanRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    // Steppers send absolute values; a fair lock keeps two quick taps committing in tap order.
    private val writeOrder = Mutex()

    val uiState = combine(repo.configFlow, settings.unitFlow) { config, unit ->
        YouUiState(config, unit, SetupStateBuilder.bodyweightDisplay(config, unit))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), YouUiState())

    fun setBodyweight(displayValue: Double) {
        update {
            val unit = settings.unitFlow.first()
            repo.updateLifter { it.copy(bodyweightLb = SetupStateBuilder.bodyweightLb(displayValue, unit)) }
        }
    }

    fun setAge(age: Int) {
        update { repo.updateLifter { it.copy(age = age.coerceAtLeast(1)) } }
    }

    fun setLevel(level: ExperienceLevel) {
        update { repo.updateLifter { it.copy(level = level) } }
    }

    private fun update(write: suspend () -> Unit) {
        viewModelScope.launch { writeOrder.withLock { write() } }
    }
}
