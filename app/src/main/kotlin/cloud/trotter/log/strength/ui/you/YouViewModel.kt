package cloud.trotter.log.strength.ui.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.ui.setup.SetupStateBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class YouViewModel @Inject constructor(private val repo: TrackerRepository) : ViewModel() {
    val uiState = combine(repo.configFlow, repo.unitFlow) { config, unit ->
        YouUiState(config, unit, SetupStateBuilder.bodyweightDisplay(config, unit))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), YouUiState())

    fun setBodyweight(displayValue: Double) {
        viewModelScope.launch {
            val unit = repo.unitFlow.first()
            repo.updateLifter { it.copy(bodyweightLb = SetupStateBuilder.bodyweightLb(displayValue, unit)) }
        }
    }

    fun setAge(age: Int) {
        viewModelScope.launch {
            repo.updateLifter { it.copy(age = age.coerceAtLeast(1)) }
        }
    }

    fun setLevel(level: ExperienceLevel) {
        viewModelScope.launch {
            repo.updateLifter { it.copy(level = level) }
        }
    }
}
