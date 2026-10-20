package cloud.trotter.log.strength.ui.plan

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.TrackerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PlansViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val menuPlanId = MutableStateFlow<Long?>(null)
    private val renamePlanId = savedState.getStateFlow<Long?>(RENAME_ID, null)
    private val renameDraft = savedState.getStateFlow(RENAME_DRAFT, "")
    private val selections = Channel<Unit>(Channel.BUFFERED)
    val selected = selections.receiveAsFlow()

    val uiState = combine(repo.planSummariesFlow, menuPlanId, renamePlanId, renameDraft) { plans, menu, rename, draft ->
        PlansUiState(plans, menu, rename, draft)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PlansUiState())

    fun openMenu(id: Long?) {
        menuPlanId.value = id
    }

    fun rename(id: Long) {
        menuPlanId.value = null
        viewModelScope.launch {
            val plan = repo.plansFlow.first().firstOrNull { it.id == id } ?: return@launch
            savedState[RENAME_DRAFT] = plan.name
            savedState[RENAME_ID] = id
        }
    }

    fun setRenameDraft(draft: String) {
        savedState[RENAME_DRAFT] = draft
    }

    fun dismissRename() {
        savedState[RENAME_ID] = null
        savedState[RENAME_DRAFT] = ""
    }

    fun saveRename() {
        val id = renamePlanId.value ?: return
        val name = renameDraft.value.trim()
        if (name.length !in 1..40) return
        viewModelScope.launch {
            repo.renamePlan(id, name)
            dismissRename()
        }
    }

    fun duplicate(id: Long) {
        menuPlanId.value = null
        viewModelScope.launch {
            val plan = repo.plansFlow.first().firstOrNull { it.id == id } ?: return@launch
            repo.duplicatePlan(id, context.getString(R.string.plan_copy_name, plan.name))
        }
    }

    fun archive(id: Long) {
        menuPlanId.value = null
        viewModelScope.launch { repo.archivePlan(id) }
    }

    fun restore(id: Long) {
        menuPlanId.value = null
        viewModelScope.launch { repo.restorePlan(id) }
    }

    fun select(id: Long) {
        viewModelScope.launch {
            repo.selectPlan(id)
            if (repo.selectedPlanIdFlow.first() == id) selections.send(Unit)
        }
    }

    private companion object {
        const val RENAME_ID = "plans_rename_id"
        const val RENAME_DRAFT = "plans_rename_draft"
    }
}
