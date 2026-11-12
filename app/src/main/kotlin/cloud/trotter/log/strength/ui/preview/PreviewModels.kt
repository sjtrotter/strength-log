package cloud.trotter.log.strength.ui.preview

import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.ui.day.CardioNextTime
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.day.LiftMarker
import cloud.trotter.log.strength.ui.day.NextTime

data class PreviewUiState(
    val loading: Boolean = true,
    val selection: DaySelection = DaySelection(null, ""),
    val planName: String = "",
    val dayIndex: Int = 0,
    val title: String = "",
    val emphasis: String = "",
    val lifts: List<PreviewLift> = emptyList(),
    val cardio: PreviewCardio? = null,
    val runId: String? = null,
)

data class PreviewLift(
    val name: String,
    val working: String?,
    val previous: List<PreviewPreviousResult>,
    val nextTime: NextTime? = null,
    val note: String = "",
    val programExerciseId: Long = 0L,
    val marker: LiftMarker? = null,
    val partnerMarker: LiftMarker? = null,
)
data class PreviewPreviousResult(
    val last: String?,
    val comparable: Boolean = true,
    val exerciseName: String = "",
    /** Fallback day id; the screen supplies the localized label. */
    val source: String? = null,
    val sourcePlanName: String? = null,
)
data class PreviewCardio(
    val activity: String,
    val time: String,
    val activityType: CardioActivity,
    val nextTime: CardioNextTime? = null,
)
data class PreviewActions(
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onStart: () -> Unit,
    val onNextTime: (programExerciseId: Long, weightLb: Double) -> Unit = { _, _ -> },
    val onCardioNextTime: (CardioBlock) -> Unit = {},
)
