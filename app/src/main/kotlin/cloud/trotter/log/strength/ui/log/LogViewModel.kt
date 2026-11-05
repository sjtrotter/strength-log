package cloud.trotter.log.strength.ui.log

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.HistoryRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.TodayTarget
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.SessionTonnageRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.Bodyweight
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.time.CivilTimeSource
import cloud.trotter.log.strength.transfer.health.BodyweightPrompt
import cloud.trotter.log.strength.transfer.health.ExternalSessionFormatter
import cloud.trotter.log.strength.transfer.health.ExternalSessionRow
import cloud.trotter.log.strength.transfer.health.HealthConnectReader
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Log screen ViewModel (PLAN.md A1; Health Connect read path; journal
 * sections per docs/briefs/journal.md). An editable, reverse-chronological
 * view of the user's own session history, led by the three derived journal
 * sections, plus a Health Connect section that lists other apps' strength
 * sessions (marked external) and surfaces the bodyweight-GOAL prompt.
 *
 * Own-history: a session's sets are fetched lazily on expand and cached in
 * [expandedSets]. Which session is expanded, which month the calendar is
 * showing, and whether the bodyweight prompt was dismissed live in
 * [SavedStateHandle] (PLAN.md A6: ephemeral UI state survives rotation/process
 * death).
 *
 * The journal sections cost two aggregate queries for the whole screen
 * ([HistoryRepository.topSetHistoryFlow], [HistoryRepository.sessionTonnageFlow])
 * plus the session list the screen already streams — never one query per card.
 * All of their shaping is [JournalBuilder]'s.
 *
 * The Health Connect reads are entirely degrade-safe: with no provider [healthData]
 * stays empty and the section hides itself, so the Log screen is fully functional
 * without Health Connect (A3). What the section does *not* hide is the connection
 * itself: [onResumed] re-reads the workout-write grant every time the screen
 * resumes, so a permission that was never given — or was revoked in the Health
 * Connect app — reads as "not connected" on the next visit instead of looking
 * exactly like a working export.
 *
 * "Today" comes from [CivilTimeSource], not from a `Clock` read when some other
 * flow happens to emit — see [journal].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LogViewModel @Inject constructor(
    private val repo: HistoryRepository,
    private val healthReader: HealthConnectReader,
    private val sessionPublisher: SessionPublisher,
    private val shareCardService: ShareCardService,
    private val savedState: SavedStateHandle,
    private val civilTimeSource: CivilTimeSource,
    private val settings: SettingsStore,
    private val plans: PlanRepository,
    private val catalog: CatalogRepository,
    tracker: TrackerRepository,
) : ViewModel() {

    private val expandedSessionId: StateFlow<Long?> = savedState.getStateFlow(KEY_EXPANDED, null)
    private val expandedSets = MutableStateFlow<Map<Long, List<SessionSetEntity>>>(emptyMap())
    private val editingSessionId: StateFlow<Long?> = savedState.getStateFlow(KEY_EDITING, null)
    private val pendingDeletion = MutableStateFlow<PendingDeletion?>(null)
    private var deletionExpiry: Job? = null
    private val editMutex = Mutex()
    private val bodyweightDismissed: StateFlow<Boolean> = savedState.getStateFlow(KEY_BW_DISMISSED, false)
    private val healthData = MutableStateFlow(HealthData())

    /** True for the length of one backfill run — a bare field on purpose: it is
     *  the spinner on a button, not user data, and a process death during the
     *  run simply leaves the (unset) one-shot flag offering it again. */
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

    private val summariesWithNotes = combine(repo.sessionSummariesFlow, repo.sessionNotesFlow) { summaries, notes ->
        summaries to notes
    }

    private val ownSessions = combine(
        summariesWithNotes,
        settings.unitFlow,
        expandedSessionId,
        expandedSets,
        editingSessionId,
    ) { summariesAndNotes, unit, expandedId, setsCache, editingId ->
        summariesAndNotes.first.map { summary ->
            buildItem(summary, unit, expandedId, setsCache[summary.session.id], editingId, summariesAndNotes.second[summary.session.id].orEmpty())
        }
    }

    private val historyItems = combine(ownSessions, repo.cardioSessionsFlow, pendingDeletion, repo.cardioSetResultsFlow) { strength, cardio, deleted, sets ->
        val items = LogScreenBuilder.interleave(strength, cardio.map {
            LogScreenBuilder.cardioItem(it, sets[it.id].orEmpty())
        }).toMutableList()
        deleted?.let { pending ->
            items.add(pending.index.coerceIn(0, items.size), pending.item.copy(undoPending = true, editing = false))
        }
        items
    }

    private val mainLifts = combine(
        plans.programFlow,
        catalog.catalogFlow,
        plans.configFlow,
    ) { program, catalog, config -> JournalBuilder.mainLifts(program, catalog, config) }

    private val journalHistory = combine(
        repo.topSetHistoryFlow,
        repo.sessionTonnageFlow,
        repo.sessionSummariesFlow,
        settings.unitFlow,
        repo.cardioSessionsFlow,
        ::JournalHistory,
    )

    /**
     * The journal's three sections all hang off *today* — the today marker, the
     * month grid, the twelve-week window — so the civil day is an upstream flow
     * like any other. When the day turns under a parked screen, or the device
     * changes zone, this re-emits and the sections are rebuilt in the new day
     * and the new zone; a `Clock` read inside the lambda could only ever be as
     * fresh as the last database change.
     */
    private val journal = combine(
        journalHistory,
        mainLifts,
        calendarOffset,
        civilTimeSource.civilTime,
    ) { history, mains, offset, now ->
        JournalUiState(
            trajectories = JournalBuilder.trajectories(mains, history.topSets, history.unit, now.zone),
            volume = JournalBuilder.volume(history.tonnage, history.unit, now.date, now.zone),
            calendar = JournalBuilder.calendar(history.sessions, offset, now.date, now.zone, history.cardio),
        )
    }

    private val healthUi = combine(
        healthData,
        plans.configFlow,
        settings.unitFlow,
        bodyweightDismissed,
    ) { health, config, unit, dismissed -> buildHealthUi(health, config.bodyweightLb, unit, dismissed) }

    private val nextDay = tracker.todayTargetFlow.flatMapLatest { target ->
        if (target is TodayTarget.Day) plans.programFor(target.plan.id).map { it.dayToShow(target.dayId)?.id }
        else flowOf(null)
    }.distinctUntilChanged()
    private val programState = combine(plans.programFlow, nextDay) { program, dayId ->
        (program.days.isNotEmpty() || dayId != null) to dayId
    }

    private val backfillState = combine(settings.healthBackfillDoneFlow, backfillRunning, ::BackfillState)

    val uiState: StateFlow<LogUiState> = combine(
        historyItems,
        journal,
        healthUi,
        programState,
        backfillState,
    ) { sessions, journalState, health, program, backfill ->
        LogUiState(
            sessions = sessions,
            journal = journalState,
            // The offer counts the sessions this screen is already showing, so it
            // needs no query of its own and can't disagree with the list.
            health = health.copy(backfill = backfillOffer(health.publishing, backfill, sessions.size)),
            hasProgram = program.first,
            nextDayId = program.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogUiState())

    init {
        // No onResumed() here: the route drives it on every resume, so the grant
        // is read when the screen is actually in front rather than once when this
        // ViewModel happened to be constructed.
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

    fun toggleEdit(sessionId: Long) {
        savedState[KEY_EDITING] = if (editingSessionId.value == sessionId) null else sessionId
        if (expandedSets.value[sessionId] == null) fetchExpandedSets(sessionId)
    }

    fun updateSessionSet(sessionId: Long, setId: Long, transform: (SessionSetEntity) -> SessionSetEntity) {
        viewModelScope.launch {
            val changed = editMutex.withLock {
                val current = expandedSets.value[sessionId]?.firstOrNull { it.id == setId } ?: return@withLock false
                val updated = transform(current)
                repo.updateSessionSet(updated)
                expandedSets.update { cache ->
                    cache + (sessionId to cache.getValue(sessionId).map { if (it.id == setId) updated else it })
                }
                true
            }
            if (changed) sessionPublisher.replace(sessionId)
        }
    }

    fun updateSessionSetWeight(sessionId: Long, setId: Long, displayWeight: Double) {
        viewModelScope.launch {
            val unit = settings.unitFlow.first()
            updateSessionSet(sessionId, setId) { it.copy(weightLb = unit.toLb(displayWeight)) }
        }
    }

    fun updateSessionSetReps(sessionId: Long, setId: Long, reps: Int) =
        updateSessionSet(sessionId, setId) { it.copy(reps = reps) }

    fun updateSessionSetSeconds(sessionId: Long, setId: Long, seconds: Int) =
        updateSessionSet(sessionId, setId) { it.copy(seconds = seconds) }

    fun updateSessionSetDone(sessionId: Long, setId: Long, done: Boolean) =
        updateSessionSet(sessionId, setId) { it.copy(done = done) }

    fun setSessionNote(sessionId: Long, text: String) {
        viewModelScope.launch { repo.setSessionNote(sessionId, text) }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            val currentItems = uiState.value.sessions
            val index = currentItems.indexOfFirst { it.sessionId == sessionId }
            val item = currentItems.getOrNull(index) ?: return@launch
            val deleted = repo.deleteSession(sessionId) ?: return@launch
            savedState[KEY_EDITING] = null
            savedState[KEY_EXPANDED] = null
            expandedSets.update { it - sessionId }
            pendingDeletion.value = PendingDeletion(item, deleted, index)
            sessionPublisher.delete(sessionId)
            deletionExpiry?.cancel()
            deletionExpiry = launch {
                delay(UNDO_WINDOW_MS)
                pendingDeletion.value = null
            }
        }
    }

    fun undoDeleteSession() {
        val pending = pendingDeletion.value ?: return
        pendingDeletion.value = null
        deletionExpiry?.cancel()
        viewModelScope.launch {
            repo.restoreSession(pending.deleted)
            sessionPublisher.publish(pending.deleted.session.id)
        }
    }

    /**
     * The SHARE tap (docs/briefs/session-share.md §1/§4) — the only path
     * that ever produces a share intent. Rendering runs off the main thread
     * inside [ShareCardService]; once it's back, [pendingShare] carries the
     * built intent for the UI to launch.
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

    /** The Health Connect permission-request contract for the screen's launcher:
     *  permissions are requested lazily, from this entry point, only when the
     *  user taps Connect. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        healthReader.permissionRequestContract()

    val requestedPermissions: Set<String> get() = healthReader.requestedPermissions

    /**
     * Everything this screen re-reads when it comes back in front. Both facts
     * can have changed while it was away without anything this ViewModel
     * observes having emitted: the Health Connect grant lives in another app,
     * and the civil day belongs to the device.
     *
     * Leaving the screen for longer than [STOP_TIMEOUT_MS] restarts civil-time
     * collection on its own; the Health Connect grant is only re-read here.
     * This hook closes the shorter gap, where the user glances away and comes
     * straight back.
     */
    fun onResumed() {
        refreshHealth()
        civilTimeSource.refresh()
    }

    /** Re-reads Health Connect — on every resume of the Log screen and after a
     *  permission result. The grant is queried, never cached: that is what makes a
     *  permission revoked elsewhere show up here next visit. The reader is already
     *  degrade-safe; this extra guard means even an unexpected provider failure
     *  can only leave the section empty, never crash the Log screen (A3). */
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
     * The backfill tap: publishes every logged session, then marks the one-shot
     * flag — but only if the publisher reports that all of them landed. A
     * partial failure leaves the flag unset, so the offer is still standing on
     * the next visit rather than claiming a success that didn't happen.
     */
    fun publishPastWorkouts() {
        if (backfillRunning.value) return
        backfillRunning.value = true
        viewModelScope.launch {
            try {
                val sessionIds = repo.sessionSummariesFlow.first().map { it.session.id }
                val cardioIds = repo.cardioSessionsFlow.first().map { it.id }
                val strengthPublished = sessionPublisher.publishAll(sessionIds)
                val cardioPublished = sessionPublisher.publishAllCardio(cardioIds)
                if (strengthPublished && cardioPublished) settings.setHealthBackfillDone()
            } finally {
                backfillRunning.value = false
            }
        }
    }

    /** Applies the Health Connect bodyweight to the app's config (the user's
     *  explicit choice — never automatic). Updates config via the repository's
     *  SettingsStore surface, as the You screen does. */
    fun applyBodyweightPrompt() {
        viewModelScope.launch {
            val config = plans.configFlow.first()
            val data = BodyweightPrompt.evaluate(healthData.value.latestWeightLb, config.bodyweightLb) ?: return@launch
            plans.updateLifter { it.copy(bodyweightLb = BodyweightPrompt.appliedBodyweightLb(data)) }
            savedState[KEY_BW_DISMISSED] = true
        }
    }

    fun dismissBodyweightPrompt() {
        savedState[KEY_BW_DISMISSED] = true
    }

    private fun buildHealthUi(
        data: HealthData,
        configBodyweightLb: Double,
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
                    currentDisplay = WeightStepper.format(Bodyweight.display(it.currentConfigLb, unit)),
                    healthConnectDisplay = WeightStepper.format(Bodyweight.display(it.healthConnectLb, unit)),
                )
            },
        )
    }

    private fun buildItem(
        summary: SessionSummaryRow,
        unit: WeightUnit,
        expandedId: Long?,
        cachedSets: List<SessionSetEntity>?,
        editingId: Long?,
        note: String,
    ): SessionListItem {
        val session = summary.session
        val expanded = session.id == expandedId
        return SessionListItem(
            sessionId = session.id,
            dateDisplay = LogScreenBuilder.dateDisplay(session.completedAt),
            dayLetter = session.dayId,
            dayIndex = Program.accentIndex(session.dayId),
            dayTitle = session.dayTitle,
            setCount = summary.setCount,
            bodyweightDisplay = LogScreenBuilder.bodyweightDisplay(session.bodyweightLb, unit),
            expanded = expanded,
            exerciseGroups = if (expanded && cachedSets != null) {
                LogScreenBuilder.groupByExercise(cachedSets, unit)
            } else {
                null
            },
            completedAt = session.completedAt,
            editing = session.id == editingId,
            note = note,
            unit = unit,
            planName = session.planName,
            planId = session.planId,
        )
    }

    /**
     * The one-shot backfill offer, or null when there is nothing to offer: no
     * export is happening ([publishing] false — a backfill would write nothing
     * and then wrongly mark itself done), the backfill has already run, or there
     * is no history yet.
     *
     * The gap this contract accepts: "done" is one flag, not per-session publish
     * state, so a session completed *after* a successful backfill while the grant
     * happened to be off is never offered again. Closing it would mean tracking a
     * published/unpublished bit per session, or re-offering after every workout —
     * both worse than the miss, since the grant being off is itself visible on
     * this screen.
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
        val cardio: List<cloud.trotter.log.strength.data.db.entity.CardioSessionEntity>,
    )

    /** The raw Health Connect read snapshot, before display formatting. */
    private data class HealthData(
        val available: Boolean = false,
        val publishing: Boolean = false,
        val externalSessions: List<ExternalSessionRow> = emptyList(),
        val latestWeightLb: Double? = null,
    )

    private data class PendingDeletion(
        val item: SessionListItem,
        val deleted: HistoryRepository.DeletedSession,
        val index: Int,
    )

    private companion object {
        const val KEY_EXPANDED = "log_expanded_session"
        const val KEY_BW_DISMISSED = "log_bodyweight_dismissed"
        const val KEY_MONTH_OFFSET = "log_calendar_month_offset"
        const val KEY_EDITING = "log_editing_session"
        const val STOP_TIMEOUT_MS = 5_000L
        const val UNDO_WINDOW_MS = 5_000L
    }
}
