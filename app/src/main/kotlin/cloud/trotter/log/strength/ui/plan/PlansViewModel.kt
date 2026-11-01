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

    private val newPlanPage = savedState.getStateFlow<String?>(NEW_PAGE, null)
    private val newPlanName = savedState.getStateFlow(NEW_NAME, "")
    private val creating = MutableStateFlow(false)
    private val newPlanState = combine(newPlanPage, newPlanName, creating) { page, name, busy ->
        Triple(page, name, busy)
    }
    private val existingState = combine(repo.planSummariesFlow, menuPlanId, renamePlanId, renameDraft) { plans, menu, rename, draft ->
        PlansUiState(plans, menu, rename, draft)
    }
    val uiState = combine(existingState, newPlanState) { state, new ->
        state.copy(newPlanPage = new.first, newPlanName = new.second, creating = new.third)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PlansUiState())

    fun showNewPlan() { savedState[NEW_PAGE] = "choice" }
    fun startEmpty() { savedState[NEW_PAGE] = "name" }
    fun setNewPlanName(name: String) { if (!creating.value) savedState[NEW_NAME] = name }
    fun dismissNewPlan() {
        if (creating.value) return
        savedState[NEW_PAGE] = null
        savedState[NEW_NAME] = ""
        savedState[CREATED_ID] = null
    }

    fun createEmpty() {
        val name = newPlanName.value.trim()
        if (creating.value || name.length !in 1..40) return
        creating.value = true
        viewModelScope.launch {
            try {
                val remembered = savedState.get<Long>(CREATED_ID)?.takeIf { repo.planHasDays(it) }
                val id = remembered ?: repo.createAuthoredPlan(name, context.getString(R.string.authored_day_title, "A"))
                    .also { savedState[CREATED_ID] = it }
                repo.publishCreatedAuthoredPlan(id)
                creating.value = false
                if (repo.selectedPlanIdFlow.first() == id) {
                    dismissNewPlan()
                    selections.send(Unit)
                }
            } finally {
                creating.value = false
            }
        }
    }

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

    fun useAlongside(id: Long) {
        menuPlanId.value = null
        viewModelScope.launch { repo.useAlongside(id) }
    }

    fun pause(id: Long) {
        menuPlanId.value = null
        viewModelScope.launch { repo.pause(id) }
    }

    fun useOnly(id: Long) {
        menuPlanId.value = null
        viewModelScope.launch { repo.useOnly(id) }
    }

    fun select(id: Long) {
        menuPlanId.value = null
        viewModelScope.launch {
            repo.selectPlan(id)
            if (repo.selectedPlanIdFlow.first() == id) selections.send(Unit)
        }
    }

    private companion object {
        const val NEW_PAGE = "plans_new_page"
        const val NEW_NAME = "plans_new_name"
        const val CREATED_ID = "plans_created_id"
        const val RENAME_ID = "plans_rename_id"
        const val RENAME_DRAFT = "plans_rename_draft"
    }
}
