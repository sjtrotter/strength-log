package cloud.trotter.log.strength.ui.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.units.WeightUnit
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
class YouViewModel @Inject constructor(private val repo: TrackerRepository) : ViewModel() {
    private val mutationLock = Mutex()

    val uiState = combine(repo.configFlow, repo.unitFlow) { config, unit ->
        YouUiState(config, unit, SetupStateBuilder.bodyweightDisplay(config, unit))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), YouUiState())

    fun setBodyweight(displayValue: Double) = mutateConfig { cfg, unit ->
        cfg.copy(bodyweightLb = SetupStateBuilder.bodyweightLb(displayValue, unit))
    }

    fun setAge(age: Int) = mutateConfig { cfg, _ -> cfg.copy(age = age.coerceAtLeast(1)) }

    fun setLevel(level: ExperienceLevel) = mutateConfig { cfg, _ -> cfg.copy(level = level) }

    private fun mutateConfig(transform: (LifterConfig, WeightUnit) -> LifterConfig) {
        viewModelScope.launch {
            mutationLock.withLock {
                val cfg = repo.configFlow.first()
                val unit = repo.unitFlow.first()
                repo.setConfig(transform(cfg, unit))
            }
        }
    }
}
