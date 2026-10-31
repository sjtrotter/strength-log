package cloud.trotter.log.strength.ui.plan

import androidx.compose.runtime.Immutable
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.ui.day.DaySelection

@Immutable
data class PlanUiState(
    val name: String? = null,
    val loading: Boolean = false,
    val hasProgram: Boolean = false,
    val days: List<PlanDay> = emptyList(),
    val planId: Long? = null,
    val authored: Boolean = false,
    val menuDayId: String? = null,
    val dialog: String? = null,
    val dialogDayId: String? = null,
    val dayTitleDraft: String = "",
)

data class PlanDay(
    val dayId: String,
    val dayIndex: Int,
    val title: String,
    val liftCount: Int,
    val setCount: Int,
    val isNext: Boolean,
    val cardioActivity: CardioActivity? = null,
    val cardioSetCount: Int = 0,
)

data class PlanActions(
    val onOpenDay: (DaySelection) -> Unit,
    val onOpenPlans: () -> Unit,
    val onSetUpProgram: () -> Unit,
    val onAddDay: () -> Unit = {},
    val onDayMenu: (String?) -> Unit = {},
    val onRenameDay: (String) -> Unit = {},
    val onRemoveDay: (String) -> Unit = {},
    val onDayTitleDraft: (String) -> Unit = {},
    val onSaveDayDialog: () -> Unit = {},
    val onDismissDayDialog: () -> Unit = {},
)
