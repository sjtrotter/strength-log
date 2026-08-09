package cloud.trotter.log.strength.ui.log

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.SessionTonnageRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.health.BodyweightPrompt
import cloud.trotter.log.strength.transfer.health.ExternalSessionFormatter
import cloud.trotter.log.strength.transfer.health.ExternalSessionRow
import cloud.trotter.log.strength.transfer.health.HealthConnectReader
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Log screen ViewModel (PLAN.md A1, issue #14; #17 Health Connect read
 * path; journal sections per docs/briefs/journal.md). A read-only,
 * reverse-chronological view of the user's own session history, now led by the
 * three derived journal sections, plus a Health Connect section that lists
 * other apps' strength sessions (marked external) and surfaces the
 * bodyweight-GOAL prompt.
 *
 * Own-history: a session's sets are fetched lazily on expand and cached in
 * [expandedSets]. Which session is expanded, which month the calendar is
 * showing, and whether the bodyweight prompt was dismissed live in
 * [SavedStateHandle] (PLAN.md A6: ephemeral UI state survives rotation/process
 * death).
 *
 * The journal sections cost two aggregate queries for the whole screen
 * ([TrackerRepository.topSetHistoryFlow], [TrackerRepository.sessionTonnageFlow])
 * plus the session list the screen already streams — never one query per card.
 * All of their shaping is [JournalBuilder]'s.
 *
 * The Health Connect reads are entirely degrade-safe: with no provider [healthData]
 * stays empty and the section hides itself, so the Log screen is fully functional
 * without Health Connect (A3). What the section does *not* hide is the connection
 * itself (#158): [refreshHealth] re-reads the workout-write grant every time the
 * screen resumes, so a permission that was never given — or was revoked in the
 * Health Connect app — reads as "not connected" on the next visit instead of
 * looking exactly like a working export.
 */
@HiltViewModel
class LogViewModel @Inject constructor(
    private val repo: TrackerRepository,
    private val healthReader: HealthConnectReader,
    private val sessionPublisher: SessionPublisher,
    private val shareCardService: ShareCardService,
    private val savedState: SavedStateHandle,
    private val clock: Clock,
) : ViewModel() {

    private val expandedSessionId: StateFlow<Long?> = savedState.getStateFlow(KEY_EXPANDED, null)
    private val expandedSets = MutableStateFlow<Map<Long, List<SessionSetEntity>>>(emptyMap())
    private val bodyweightDismissed: StateFlow<Boolean> = savedState.getStateFlow(KEY_BW_DISMISSED, false)
    private val healthData = MutableStateFlow(HealthData())

    /** True for the length of one backfill run (#159) — a bare field on purpose:
     *  it is the spinner on a button, not user data, and a process death during
     *  the run simply leaves the (unset) one-shot flag offering it again. */
    private val backfillRunning = MutableStateFlow(false)

    /**
     * The share card's ACTION_SEND intent, ready for the UI to launch — a bare
     * field, deliberately not [SavedStateHandle] (same reasoning as
     * [cloud.trotter.log.strength.ui.day.DayViewModel]'s cascade
     * ceremony): a rendered [Intent] can't cross process death anyway, and
     * losing it there loses nothing the user typed — a re-tap of SHARE
     * regenerates the same card. Set only by [shareSession]; cleared by
     * [shareHandled] once the UI has launched it, so a rotation never
     * re-fires the chooser.
     */
    private val _pendingShare = MutableStateFlow<Intent?>(null)
    val pendingShare: StateFlow<Intent?> = _pendingShare.asStateFlow()

    /** Months back from the current one, never positive (journal §1.3). */
    private val calendarOffset: StateFlow<Int> = savedState.getStateFlow(KEY_MONTH_OFFSET, 0)

    private val ownSessions = combine(
        repo.sessionSummariesFlow,
        repo.unitFlow,
        expandedSessionId,
        expandedSets,
    ) { summaries, unit, expandedId, setsCache ->
        summaries.map { summary -> buildItem(summary, unit, expandedId, setsCache[summary.session.id]) }
    }

    private val mainLifts = combine(
        repo.programFlow,
        repo.catalogFlow,
        repo.configFlow,
    ) { program, catalog, config -> JournalBuilder.mainLifts(program, catalog, config) }

    private val journalHistory = combine(
        repo.topSetHistoryFlow,
        repo.sessionTonnageFlow,
        repo.sessionSummariesFlow,
        repo.unitFlow,
        ::JournalHistory,
    )

    private val journal = combine(journalHistory, mainLifts, calendarOffset) { history, mains, offset ->
        val today = LocalDate.now(clock)
        JournalUiState(
            trajectories = JournalBuilder.trajectories(mains, history.topSets, history.unit, clock.zone),
            volume = JournalBuilder.volume(history.tonnage, history.unit, today, clock.zone),
            calendar = JournalBuilder.calendar(history.sessions, offset, today, clock.zone),
        )
    }

    private val healthUi = combine(
        healthData,
        repo.configFlow,
        repo.unitFlow,
        bodyweightDismissed,
    ) { health, config, unit, dismissed -> buildHealthUi(health, config.bodyweightLb, unit, dismissed) }

    /** Only the empty state reads this (#127), so it is the one bit of the
     *  program the journal needs — not the program itself. */
    private val hasProgram = repo.programFlow.map { it.days.isNotEmpty() }.distinctUntilChanged()

    private val backfillState = combine(repo.healthBackfillDoneFlow, backfillRunning, ::BackfillState)

    val uiState: StateFlow<LogUiState> = combine(
        ownSessions,
        journal,
        healthUi,
        hasProgram,
        backfillState,
    ) { sessions, journalState, health, programExists, backfill ->
        LogUiState(
            sessions = sessions,
            journal = journalState,
            // The offer counts the sessions this screen is already showing, so it
            // needs no query of its own and can't disagree with the list.
            health = health.copy(backfill = backfillOffer(health.publishing, backfill, sessions.size)),
            hasProgram = programExists,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogUiState())

    init {
        // No refreshHealth() here: the route drives it on every resume (#158), so
        // the grant is read when the screen is actually in front rather than once
        // when this ViewModel happened to be constructed.
        //
        // expandedSessionId survives process death via SavedStateHandle, but
        // expandedSets is an in-memory cache and doesn't — without this, a
        // restored VM shows the row expanded with no content until the user
        // collapses and re-expands it. Fetch through the same path as a tap.
        expandedSessionId.value?.let(::fetchExpandedSets)
    }

    /** Expanding a session that isn't cached yet fetches its sets once; expanding
     *  an already-expanded session collapses it instead (accordion, one at a time). */
    fun toggleExpanded(sessionId: Long) {
        val next = if (expandedSessionId.value == sessionId) null else sessionId
        savedState[KEY_EXPANDED] = next
        if (next != null) fetchExpandedSets(next)
    }

    /**
     * The SHARE tap (docs/briefs/session-share.md §1/§4 acceptance #1) — the
     * only path that ever produces a share intent. Rendering runs off the
     * main thread inside [ShareCardService]; once it's back, [pendingShare]
     * carries the built intent for the UI to launch.
     */
    fun shareSession(sessionId: Long) {
        viewModelScope.launch {
            shareCardService.buildShareIntent(sessionId)?.let { _pendingShare.value = it }
        }
    }

    /** Called once the UI has launched [pendingShare], so it doesn't fire again. */
    fun shareHandled() {
        _pendingShare.value = null
    }

    private fun fetchExpandedSets(sessionId: Long) {
        if (sessionId in expandedSets.value) return
        viewModelScope.launch {
            val sets = repo.sessionSets(sessionId)
            expandedSets.update { it + (sessionId to sets) }
        }
    }

    /** Pages the calendar by [delta] months, clamped at the current month —
     *  there is nothing to show ahead of today (journal §1.3). */
    fun pageCalendar(delta: Int) {
        savedState[KEY_MONTH_OFFSET] = (calendarOffset.value + delta).coerceAtMost(0)
    }

    /** The Health Connect permission-request contract for the screen's launcher
     *  (#17): permissions are requested lazily, from this entry point, only when
     *  the user taps Connect. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        healthReader.permissionRequestContract()

    val requestedPermissions: Set<String> get() = healthReader.requestedPermissions

    /** Re-reads Health Connect — on every resume of the Log screen and after a
     *  permission result. The grant is queried, never cached (#158): that is what
     *  makes a permission revoked elsewhere show up here next visit. The reader is
     *  already degrade-safe; this extra guard means even an unexpected provider
     *  failure can only leave the section empty, never crash the Log screen (A3). */
    fun refreshHealth() {
        viewModelScope.launch {
            healthData.value = runCatching {
                if (!healthReader.isAvailable()) {
                    HealthData(available = false)
                } else {
                    HealthData(
                        available = true,
                        publishing = healthReader.publishesWorkouts(),
                        externalSessions = ExternalSessionFormatter.format(healthReader.externalWorkouts()),
                        latestWeightLb = healthReader.latestBodyweightLb(),
                    )
                }
            }.getOrDefault(HealthData())
        }
    }

    /**
     * The backfill tap (#159): publishes every logged session, then marks the
     * one-shot flag — but only if the publisher reports that all of them landed.
     * A partial failure leaves the flag unset, so the offer is still standing on
     * the next visit rather than claiming a success that didn't happen.
     */
    fun publishPastWorkouts() {
        if (backfillRunning.value) return
        backfillRunning.value = true
        viewModelScope.launch {
            try {
                val sessionIds = repo.sessionSummariesFlow.first().map { it.session.id }
                if (sessionPublisher.publishAll(sessionIds)) repo.setHealthBackfillDone()
            } finally {
                backfillRunning.value = false
            }
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
            publishing = data.publishing,
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

    /**
     * The one-shot backfill offer (#159), or null when there is nothing to
     * offer: no export is happening ([publishing] false — a backfill would
     * write nothing and then wrongly mark itself done), the backfill has already
     * run, or there is no history yet.
     *
     * The gap this contract accepts: "done" is one flag, not per-session publish
     * state, so a session completed *after* a successful backfill while the grant
     * happened to be off is never offered again. Closing it would mean tracking a
     * published/unpublished bit per session, or re-offering after every workout —
     * both worse than the miss, since the grant being off is itself now visible
     * on this screen (#158).
     */
    private fun backfillOffer(publishing: Boolean, state: BackfillState, sessionCount: Int): BackfillOfferUi? {
        if (!publishing || state.done || sessionCount == 0) return null
        return BackfillOfferUi(
            label = LogScreenBuilder.backfillLabel(sessionCount, state.running),
            enabled = !state.running,
        )
    }

    /** The two facts the backfill offer turns on: whether it has ever completed,
     *  and whether it is running right now. */
    private data class BackfillState(val done: Boolean, val running: Boolean)

    /** The three history reads the journal sections share, kept together so the
     *  section builders run off one combine rather than one per card. */
    private data class JournalHistory(
        val topSets: List<TopSetRow>,
        val tonnage: List<SessionTonnageRow>,
        val sessions: List<SessionSummaryRow>,
        val unit: WeightUnit,
    )

    /** The raw Health Connect read snapshot, before display formatting. */
    private data class HealthData(
        val available: Boolean = false,
        val publishing: Boolean = false,
        val externalSessions: List<ExternalSessionRow> = emptyList(),
        val latestWeightLb: Double? = null,
    )

    private companion object {
        const val KEY_EXPANDED = "log_expanded_session"
        const val KEY_BW_DISMISSED = "log_bodyweight_dismissed"
        const val KEY_MONTH_OFFSET = "log_calendar_month_offset"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
