package io.github.sjtrotter.strengthlog.ui.log

import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.dao.SessionSummaryRow
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.transfer.health.BodyweightPrompt
import io.github.sjtrotter.strengthlog.transfer.health.ExternalSessionFormatter
import io.github.sjtrotter.strengthlog.transfer.health.ExternalSessionRow
import io.github.sjtrotter.strengthlog.transfer.health.HealthConnectReader
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Log screen ViewModel (PLAN.md A1, issue #14; #17 Health Connect read
 * path). A read-only, reverse-chronological view of the user's own session
 * history, plus a Health Connect section that lists other apps' strength
 * sessions (marked external) and surfaces the bodyweight-GOAL prompt.
 *
 * Own-history: a session's sets are fetched lazily on expand and cached in
 * [expandedSets]. Which session is expanded, and whether the bodyweight prompt
 * was dismissed, live in [SavedStateHandle] (PLAN.md A6: ephemeral UI state
 * survives rotation/process death).
 *
 * The Health Connect reads are entirely degrade-safe: with no provider or no
 * permission [healthData] stays empty and the section hides itself, so the Log
 * screen is fully functional without Health Connect (A3).
 */
@HiltViewModel
class LogViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val healthReader: HealthConnectReader,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val expandedSessionId: StateFlow<Long?> = savedState.getStateFlow(KEY_EXPANDED, null)
    private val expandedSets = MutableStateFlow<Map<Long, List<SessionSetEntity>>>(emptyMap())
    private val bodyweightDismissed: StateFlow<Boolean> = savedState.getStateFlow(KEY_BW_DISMISSED, false)
    private val healthData = MutableStateFlow(HealthData())

    private val ownSessions = combine(
        repo.sessionSummariesFlow,
        repo.unitFlow,
        expandedSessionId,
        expandedSets,
    ) { summaries, unit, expandedId, setsCache ->
        summaries.map { summary -> buildItem(summary, unit, expandedId, setsCache[summary.session.id]) }
    }

    val uiState: StateFlow<LogUiState> = combine(
        ownSessions,
        repo.unitFlow,
        repo.configFlow,
        healthData,
        bodyweightDismissed,
    ) { sessions, unit, config, health, dismissed ->
        LogUiState(
            sessions = sessions,
            health = buildHealthUi(health, config.bodyweightLb, unit, dismissed),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogUiState())

    init {
        refreshHealth()
    }

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

    /** The Health Connect permission-request contract for the screen's launcher
     *  (#17): permissions are requested lazily, from this entry point, only when
     *  the user taps Connect. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        healthReader.permissionRequestContract()

    val requestedPermissions: Set<String> get() = healthReader.requestedPermissions

    /** Re-reads Health Connect after a permission result (or on first open). The
     *  reader is already degrade-safe; this extra guard means even an unexpected
     *  provider failure can only leave the section empty, never crash the Log
     *  screen (A3). */
    fun refreshHealth() {
        viewModelScope.launch {
            healthData.value = runCatching {
                if (!healthReader.isAvailable()) {
                    HealthData(available = false)
                } else {
                    val granted = healthReader.grantedPermissions()
                    HealthData(
                        available = true,
                        connected = granted.isNotEmpty(),
                        externalSessions = ExternalSessionFormatter.format(healthReader.externalWorkouts()),
                        latestWeightLb = healthReader.latestBodyweightLb(),
                    )
                }
            }.getOrDefault(HealthData())
        }
    }

    /** Applies the Health Connect bodyweight to the app's config (the user's
     *  explicit choice — never automatic). Updates config via the repository's
     *  SettingsStore surface, exactly as the Setup screen does. */
    fun applyBodyweightPrompt() {
        viewModelScope.launch {
            val config = repo.configFlow.first()
            val data = BodyweightPrompt.evaluate(healthData.value.latestWeightLb, config.bodyweightLb) ?: return@launch
            repo.setConfig(config.copy(bodyweightLb = BodyweightPrompt.appliedBodyweightLb(data)))
            savedState[KEY_BW_DISMISSED] = true
        }
    }

    fun dismissBodyweightPrompt() {
        savedState[KEY_BW_DISMISSED] = true
    }

    private fun buildHealthUi(
        data: HealthData,
        configBodyweightLb: Int,
        unit: WeightUnit,
        dismissed: Boolean,
    ): HealthSectionUi {
        val promptData = if (dismissed) {
            null
        } else {
            BodyweightPrompt.evaluate(data.latestWeightLb, configBodyweightLb)
        }
        return HealthSectionUi(
            available = data.available,
            connected = data.connected,
            externalSessions = data.externalSessions,
            bodyweightPrompt = promptData?.let {
                BodyweightPromptUi(
                    currentDisplay = WeightStepper.format(unit.fromLb(it.currentConfigLb.toDouble())),
                    healthConnectDisplay = WeightStepper.format(unit.fromLb(it.healthConnectLb)),
                )
            },
        )
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

    /** The raw Health Connect read snapshot, before display formatting. */
    private data class HealthData(
        val available: Boolean = false,
        val connected: Boolean = false,
        val externalSessions: List<ExternalSessionRow> = emptyList(),
        val latestWeightLb: Double? = null,
    )

    private companion object {
        const val KEY_EXPANDED = "log_expanded_session"
        const val KEY_BW_DISMISSED = "log_bodyweight_dismissed"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
