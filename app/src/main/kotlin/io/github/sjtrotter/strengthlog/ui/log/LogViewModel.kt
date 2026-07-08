package io.github.sjtrotter.strengthlog.ui.log

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.dao.SessionSummaryRow
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Log screen ViewModel (PLAN.md A1, issue #14): a read-only,
 * reverse-chronological view of [TrackerRepository.sessionSummariesFlow]. A
 * session's sets are fetched lazily on expand and cached in [expandedSets] so
 * re-collapsing/re-expanding the same row doesn't re-query. Which session is
 * expanded lives in [SavedStateHandle] (PLAN.md A6: ephemeral UI state
 * survives rotation/process death).
 */
@HiltViewModel
class LogViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val expandedSessionId: StateFlow<Long?> = savedState.getStateFlow(KEY_EXPANDED, null)
    private val expandedSets = MutableStateFlow<Map<Long, List<SessionSetEntity>>>(emptyMap())

    val uiState: StateFlow<LogUiState> = combine(
        repo.sessionSummariesFlow,
        repo.unitFlow,
        expandedSessionId,
        expandedSets,
    ) { summaries, unit, expandedId, setsCache ->
        LogUiState(
            sessions = summaries.map { summary -> buildItem(summary, unit, expandedId, setsCache[summary.session.id]) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogUiState())

    /** Expanding a session that isn't cached yet fetches its sets once; expanding
     *  an already-expanded session collapses it instead (accordion, one at a time). */
    fun toggleExpanded(sessionId: Long) {
        val next = if (expandedSessionId.value == sessionId) null else sessionId
        savedState[KEY_EXPANDED] = next
        if (next != null && next !in expandedSets.value) {
            viewModelScope.launch {
                val sets = repo.sessionSets(next)
                expandedSets.update { it + (next to sets) }
            }
        }
    }

    private fun buildItem(
        summary: SessionSummaryRow,
        unit: WeightUnit,
        expandedId: Long?,
        cachedSets: List<SessionSetEntity>?,
    ): SessionListItem {
        val session = summary.session
        val expanded = session.id == expandedId
        return SessionListItem(
            sessionId = session.id,
            dateDisplay = LogScreenBuilder.dateDisplay(session.completedAt),
            dayLetter = session.dayId,
            dayIndex = LogScreenBuilder.dayIndex(session.dayId),
            dayTitle = session.dayTitle,
            setCount = summary.setCount,
            bodyweightDisplay = LogScreenBuilder.bodyweightDisplay(session.bodyweightLb, unit),
            expanded = expanded,
            exerciseGroups = if (expanded && cachedSets != null) {
                LogScreenBuilder.groupByExercise(cachedSets, unit)
            } else {
                null
            },
        )
    }

    private companion object {
        const val KEY_EXPANDED = "log_expanded_session"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
