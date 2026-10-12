package cloud.trotter.log.strength.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.TrackerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class PlanViewModel @Inject constructor(repo: TrackerRepository) : ViewModel() {
    val uiState: StateFlow<PlanUiState> = combine(
        repo.programFlow,
        repo.suggestedDayFlow,
        repo.configFlow,
        repo.catalogFlow,
    ) { program, nextDayId, config, catalog ->
        val days = PlanScreenBuilder.days(program, nextDayId, config, catalog)
        PlanUiState(hasProgram = days.isNotEmpty(), days = days)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PlanUiState(loading = true),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
