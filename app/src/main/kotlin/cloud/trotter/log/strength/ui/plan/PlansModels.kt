package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.data.PlanSummary

data class PlansUiState(
    val plans: List<PlanSummary> = emptyList(),
    val menuPlanId: Long? = null,
    val renamePlanId: Long? = null,
    val renameDraft: String = "",
    val newPlanPage: String? = null,
    val newPlanName: String = "",
    val creating: Boolean = false,
)

data class PlansActions(
    val onBack: () -> Unit,
    val onNewPlan: () -> Unit,
    val onSelect: (Long) -> Unit,
    val onMenu: (Long?) -> Unit,
    val onRename: (Long) -> Unit,
    val onRenameDraft: (String) -> Unit,
    val onSaveRename: () -> Unit,
    val onDismissRename: () -> Unit,
    val onDuplicate: (Long) -> Unit,
    val onArchive: (Long) -> Unit,
    val onRestore: (Long) -> Unit,
    val onShowNewPlan: () -> Unit = {},
    val onStartEmpty: () -> Unit = {},
    val onNewPlanName: (String) -> Unit = {},
    val onCreateEmpty: () -> Unit = {},
    val onDismissNewPlan: () -> Unit = {},
)
