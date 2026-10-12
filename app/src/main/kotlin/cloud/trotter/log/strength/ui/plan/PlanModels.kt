package cloud.trotter.log.strength.ui.plan

import androidx.compose.runtime.Immutable

@Immutable
data class PlanUiState(
    val loading: Boolean = false,
    val hasProgram: Boolean = false,
    val days: List<PlanDay> = emptyList(),
)

data class PlanDay(
    val dayId: String,
    val dayIndex: Int,
    val title: String,
    val liftCount: Int,
    val setCount: Int,
    val isNext: Boolean,
)

data class PlanActions(
    val onOpenDay: (String) -> Unit,
    val onSetUpAgain: () -> Unit,
)
