package cloud.trotter.log.strength.ui.plan

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutationLock = Mutex()
    private val dialog = savedState.getStateFlow<String?>(DIALOG, null)
    private val dialogDay = savedState.getStateFlow<String?>(DAY, null)
    private val title = savedState.getStateFlow(TITLE, "")
    private val menu = savedState.getStateFlow<String?>(MENU, null)
    private val dialogState = combine(dialog, dialogDay, title, menu) { kind, day, draft, menuDay ->
        PlanUiState(dialog = kind, dialogDayId = day, dayTitleDraft = draft, menuDayId = menuDay)
    }
    private val programState = combine(
        repo.programFlow, repo.suggestedDayFlow, repo.configFlow, repo.catalogFlow, repo.selectedPlanFlow,
    ) { program, nextDayId, config, catalog, plan ->
        val days = PlanScreenBuilder.days(program, nextDayId, config, catalog)
        PlanUiState(
            name = plan?.name, hasProgram = days.isNotEmpty(), days = days,
            planId = plan?.id, authored = plan?.origin == PlanOrigin.AUTHORED.name,
        )
    }
    val uiState: StateFlow<PlanUiState> = combine(programState, dialogState) { program, draft ->
        program.copy(
            menuDayId = draft.menuDayId, dialog = draft.dialog,
            dialogDayId = draft.dialogDayId, dayTitleDraft = draft.dayTitleDraft,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PlanUiState(loading = true))

    fun openDayMenu(dayId: String?) { savedState[MENU] = dayId }

    fun addDay() {
        val id = uiState.value.takeIf { it.authored }?.planId ?: return
        viewModelScope.launch {
            mutationLock.withLock {
                val days = repo.programFor(id).first().days
                val letter = ('A'..'G').firstOrNull { candidate -> days.none { it.id == candidate.toString() } }
                    ?: return@withLock
                repo.addDay(id, context.getString(R.string.authored_day_title, letter.toString()))
            }
        }
    }

    fun renameDay(dayId: String) = openDialog("rename", dayId)
    fun removeDay(dayId: String) = openDialog("remove", dayId)

    private fun openDialog(kind: String, dayId: String) {
        val state = uiState.value
        if (!state.authored) return
        val day = state.days.firstOrNull { it.dayId == dayId } ?: return
        savedState[PLAN] = state.planId
        savedState[DAY] = dayId
        savedState[TITLE] = day.title
        savedState[DIALOG] = kind
        savedState[MENU] = null
    }

    fun setDayTitleDraft(value: String) { savedState[TITLE] = value }

    fun dismissDayDialog() {
        savedState[DIALOG] = null
        savedState[DAY] = null
        savedState[PLAN] = null
        savedState[TITLE] = ""
    }

    fun saveDayDialog() {
        val id = savedState.get<Long>(PLAN) ?: return
        val dayId = dialogDay.value ?: return
        val kind = dialog.value ?: return
        val draft = title.value.trim()
        if (kind == "rename" && draft.length !in 1..30) return
        viewModelScope.launch {
            mutationLock.withLock {
                if (kind == "rename") repo.renameDay(id, dayId, draft) else repo.removeDay(id, dayId)
                dismissDayDialog()
            }
        }
    }

    private companion object {
        const val DIALOG = "plan_day_dialog"
        const val DAY = "plan_dialog_day"
        const val PLAN = "plan_dialog_plan"
        const val TITLE = "plan_day_title"
        const val MENU = "plan_day_menu"
    }
}
