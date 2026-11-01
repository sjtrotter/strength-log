package cloud.trotter.log.strength.ui.preview

import cloud.trotter.log.strength.ui.day.DaySelection

data class PreviewUiState(
    val loading: Boolean = true,
    val selection: DaySelection = DaySelection(null, ""),
    val planName: String = "",
    val dayIndex: Int = 0,
    val title: String = "",
    val emphasis: String = "",
    val lifts: List<PreviewLift> = emptyList(),
    val cardio: PreviewCardio? = null,
)

data class PreviewLift(val name: String, val working: String?, val last: String?)
data class PreviewCardio(val activity: String, val time: String)
data class PreviewActions(val onBack: () -> Unit, val onEdit: () -> Unit, val onStart: () -> Unit)
