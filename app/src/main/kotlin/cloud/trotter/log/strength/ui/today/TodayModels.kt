package cloud.trotter.log.strength.ui.today

import androidx.compose.runtime.Immutable
import cloud.trotter.log.strength.ui.day.DaySelection

@Immutable
data class TodayUiState(
    val hasProgram: Boolean = false,
    val nothingOnToday: Boolean = false,
    val loading: Boolean = false,
    val resume: TodayDay? = null,
    val upNext: List<TodayDay> = emptyList(),
    val otherDays: List<TodayDay> = emptyList(),
    val otherDaysExpanded: Boolean = false,
    val fact: String? = null,
)

data class TodayDay(
    val planId: Long,
    val planName: String,
    val dayId: String,
    val dayIndex: Int,
    val title: String,
    val cardioOnly: Boolean = false,
) {
    val selection: DaySelection get() = DaySelection(planId, dayId)
}

data class TodayActions(
    val onStart: (DaySelection) -> Unit,
    val onOpenSettings: () -> Unit,
    val onSetUpProgram: () -> Unit,
    val onOpenPreview: (DaySelection) -> Unit = {},
    val onResume: (DaySelection) -> Unit = {},
    val onOpenCardio: (DaySelection) -> Unit = {},
    val onToggleOtherDays: () -> Unit = {},
    val onOpenPlans: () -> Unit = {},
)
