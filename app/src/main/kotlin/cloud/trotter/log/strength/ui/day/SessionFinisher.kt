package cloud.trotter.log.strength.ui.day

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.HistoryRepository
import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.setCounts
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.NOTE_MAX_LENGTH
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.records.CardioRecordKey
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.receipt.FinishAttempt
import cloud.trotter.log.strength.ui.day.receipt.NoteSave
import cloud.trotter.log.strength.ui.day.receipt.ReceiptBuilder
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSetCounts
import cloud.trotter.log.strength.ui.day.receipt.WorkoutReceipt
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.data.UndoResult
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.ui.day.receipt.ReceiptProgress
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSlotRef
import cloud.trotter.log.strength.ui.day.receipt.ReceiptProgressState
import cloud.trotter.log.strength.ui.day.receipt.ReceiptProgressBuilder
import cloud.trotter.log.strength.ui.day.receipt.ReceiptMutation
import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionFinisher(
    private val repo: TrackerRepository,
    private val historyRepository: HistoryRepository,
    private val plans: PlanRepository,
    private val catalog: CatalogRepository,
    private val settings: SettingsStore,
    private val sessionPublisher: SessionPublisher,
    private val shareCardService: ShareCardService,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
    private val mutationLock: Mutex,
    private val clock: Clock,
    private val afterCommit: suspend (dayId: String, nextDayId: String?) -> Unit,
    private val executionActive: () -> Boolean,
) {
    val attempt = savedState.getStateFlow<FinishAttempt?>(KEY_FINISH, null)
    val receipt = savedState.getStateFlow<WorkoutReceipt?>(KEY_WORKOUT_RECEIPT, null)
    val receiptSetCounts = savedState.getStateFlow<ReceiptSetCounts?>(KEY_RECEIPT_SET_COUNTS, null)
    val receiptProgress = savedState.getStateFlow<ReceiptProgress?>(KEY_RECEIPT_PROGRESS, null)
    val progressState = combine(receiptProgress, receipt) { identity, current -> identity to current }.flatMapLatest { (identity, current) ->
        val planId = identity?.planId
        if (identity == null || planId == null) flowOf(ReceiptProgressState()) else combine(
            repo.dayTracksFlow(planId, identity.dayId), plans.slotsFor(planId, identity.dayId),
            plans.plansFlow, historyRepository.sessionsFlow,
            combine(historyRepository.cardioSessionsFlow, historyRepository.cardioSetResultsFlow) { sessions, sets -> sessions to sets },
        ) { tracks, slots, allPlans, _, cardio ->
            ReceiptProgressBuilder.build(identity, current, tracks, slots, allPlans.firstOrNull { it.id == planId },
                historyRepository, repo, cardio.first, cardio.second, plans.programFor(planId).first())
        }
    }.stateIn(scope, SharingStarted.Eagerly, ReceiptProgressState())
    private val _mutation = MutableStateFlow<ReceiptMutation?>(null)
    val mutation: StateFlow<ReceiptMutation?> = _mutation.asStateFlow()
    private val _noteSave = MutableStateFlow<NoteSave?>(null)
    val noteSave: StateFlow<NoteSave?> = _noteSave.asStateFlow()
    private val _pendingShare = MutableStateFlow<Intent?>(null)
    val pendingShare: StateFlow<Intent?> = _pendingShare.asStateFlow()
    private var job: Job? = null

    init {
        savedState.remove<SessionReceipt>("day_session_receipt")
        savedState.remove<cloud.trotter.log.strength.ui.day.receipt.ReceiptNextTimes>("day_receipt_next_times")
        savedState.remove<cloud.trotter.log.strength.ui.day.receipt.ReceiptRepNextTimes>("day_receipt_rep_next_times")
        savedState.keys().filter { it.startsWith("day_cardio_receipt_records:") }.forEach {
            savedState.remove<ArrayList<CardioReceiptRecord>>(it)
        }
        attempt.value?.takeIf { !it.failed }?.let { restored ->
            launch { finish(restored.runId, restored.planId, restored.dayId, restored.dayIndex, restored.dayTitle) }
        }
    }

    fun start(runId: String, planId: Long, dayId: String, dayIndex: Int, dayTitle: String) {
        launch { finish(runId, planId, dayId, dayIndex, dayTitle) }
    }

    fun retry() {
        if (job?.isActive == true) return
        val failed = attempt.value?.takeIf { it.failed } ?: return
        savedState[KEY_FINISH] = failed.copy(failed = false)
        launch { finish(failed.runId, failed.planId, failed.dayId, failed.dayIndex, failed.dayTitle) }
    }

    private fun launch(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                mutationLock.withLock { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                attempt.value?.let { savedState[KEY_FINISH] = it.copy(failed = true) }
            }
        }
    }

    private suspend fun finish(runId: String, planId: Long, dayId: String, dayIndex: Int, dayTitle: String) {
        val run = repo.openRunFlow.first()?.takeIf { it.id == runId }
        val archived = if (run == null) repo.sessionByRunId(runId) else null
        if (run == null && archived == null) {
            savedState[KEY_FINISH] = null
            return
        }
        val current = attempt.value ?: FinishAttempt(
            runId, planId, dayId, dayIndex, dayTitle,
            plans.plansFlow.first().firstOrNull { it.id == planId }?.name.orEmpty(),
            run?.startedAt ?: archived?.startedAt, clock.millis(),
        ).also { savedState[KEY_FINISH] = it }
        if (run != null && run.tracks.none { track -> track.sets.any { it.done } } &&
            cardioFor(planId, dayId, run.startedAt, clock.millis()) == null) {
            savedState[KEY_FINISH] = null
            return
        }
        val exercises = catalog.catalogFlow.first()
        // DataStore reads must finish before finishRun opens its Room transaction.
        val unit = settings.unitFlow.first()
        val program = plans.programFor(planId).first()
        val priorIds = run?.tracks?.map { it.exerciseId }?.distinct().orEmpty()
        var standing = if (run != null) baseline(priorIds, runId) else null
        val finished = repo.finishRunCapturing(runId) ?: run {
            savedState[KEY_FINISH] = null
            return
        }
        val sessionId = finished.sessionId
        scope.launch { sessionPublisher.publish(sessionId) }
        val tracks = requireNotNull(finished.tracks)
        val slots = requireNotNull(finished.slots)
        val ids = tracks.map { it.exerciseId }.distinct()
        if (run == null) standing = baseline(ids, runId)
        else if (standing != null && (ids - priorIds.toSet()).isNotEmpty()) {
            standing = baseline(ids - priorIds.toSet(), runId)?.let { standing.orEmpty() + it }
        }
        val groupsWithTracks = ReceiptBuilder.strengthWithTracks(tracks, slots, { exercises.find(it)?.name ?: it }, standing.orEmpty())
        val session = historyRepository.session(sessionId)
        val completedAt = session?.completedAt ?: clock.millis()
        val cardio = cardioFor(planId, dayId, session?.startedAt ?: current.startedAt, completedAt)
        val cardioUndoId = historyRepository.cardioSessionsFlow.first().filter {
            it.planId == planId && it.dayId == dayId &&
                it.completedAt in (session?.startedAt ?: current.startedAt ?: completedAt)..completedAt
        }.maxWithOrNull(compareBy({ it.completedAt }, { it.id }))?.id
        val groups = groupsWithTracks.map { it.first }
        val counts = tracks.setCounts()
        val cardioSets = cardio?.let { historyRepository.cardioSetsFor(it.id) }.orEmpty()
        val activity = cardio?.activity?.let { name -> CardioActivity.entries.firstOrNull { it.name == name } }
        val cardioStanding = if (cardio != null && activity != null) cardioBaseline(activity, dayId, cardio.startedAt) else null
        val cardioRows = if (activity != null) ReceiptBuilder.cardio(activity, cardioSets, cardioStanding.orEmpty()) else emptyList()
        val nextDayId = plans.plansFlow.first().firstOrNull { it.id == planId }?.suggestedDayId
        afterCommit(dayId, nextDayId)
        val allPlansDone = allPlansDone(completedAt)
        savedState[KEY_RECEIPT_SET_COUNTS] = ReceiptSetCounts(sessionId, counts.total, counts.remaining)
        savedState[KEY_RECEIPT_PROGRESS] = ReceiptProgress(sessionId, cardioUndoId, planId, dayId,
            groupsWithTracks.flatMapIndexed { groupIndex, (_, rowTracks) -> rowTracks.mapIndexedNotNull { rowIndex, track ->
                track.programExerciseId?.takeUnless { track.retired || track.dropped && track.sets.none { it.done } }?.let { ReceiptSlotRef(groupIndex, rowIndex, it, track.slot) }
            } })
        savedState[KEY_WORKOUT_RECEIPT] = WorkoutReceipt(
            sessionId = sessionId, cardio = false, dayId = dayId, dayIndex = dayIndex,
            dayTitle = program.days.firstOrNull { it.id == dayId }?.title ?: current.dayTitle,
            planName = current.planName, completedAt = completedAt,
            minutes = session?.startedAt?.let { ((completedAt - it) / 60_000.0).roundToInt().coerceAtLeast(1) },
            unit = unit, recordsChecked = standing != null && (cardioStanding != null || activity == null), allPlansDone = allPlansDone,
            groups = groups, activity = activity, cardioRows = cardioRows,
        )
        savedState[KEY_FINISH] = null
    }

    /** The standing bests, or null when they could not be read: the receipt then says records
     *  weren't checked instead of showing none. */
    private suspend fun baseline(ids: List<String>, runId: String): Map<String, PersonalRecord>? = try {
        historyRepository.personalRecords(ids, excludingRunId = runId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun cardioBaseline(activity: CardioActivity, dayId: String, startedAt: Long): Map<CardioRecordKey, CardioSetResult>? = try {
        historyRepository.cardioBestsFor(activity, excludedDayId = dayId, excludedStartedAt = startedAt)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun cardioFor(planId: Long, dayId: String, startedAt: Long?, completedAt: Long): CardioSessionEntity? {
        val date = Instant.ofEpochMilli(completedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        return historyRepository.cardioSessionsFlow.first().filter { entry ->
            entry.planId == planId && entry.dayId == dayId &&
                (entry.completedAt in (startedAt ?: completedAt)..completedAt ||
                    Instant.ofEpochMilli(entry.completedAt).atZone(ZoneId.systemDefault()).toLocalDate() == date)
        }.maxWithOrNull(compareBy({ it.completedAt }, { it.id }))
    }

    fun undoChanges() = mutateReceipt { identity -> repo.undoFinish(identity.sessionId, identity.cardioSessionId) }

    fun setRoughDay(rough: Boolean) = mutateReceipt(announceUndo = false) { identity ->
        identity.sessionId?.let { repo.setRoughDay(it, rough) } ?: UndoResult.Nothing
    }

    fun setCardioEffort(effort: CardioEffort?) {
        val id = progressState.value.cardioSessionId ?: return
        scope.launch {
            try {
                mutationLock.withLock { repo.setCardioEffort(id, effort) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { _mutation.value = ReceiptMutation.Failed }
        }
    }

    private fun mutateReceipt(announceUndo: Boolean = true, write: suspend (ReceiptProgress) -> UndoResult) {
        scope.launch {
            try {
                mutationLock.withLock {
                    val identity = receiptProgress.value ?: return@withLock
                    _mutation.value = when (write(identity)) {
                        UndoResult.Undone -> ReceiptMutation.Undone.takeIf { announceUndo }
                        UndoResult.DayOpen -> ReceiptMutation.DayOpen(identity.dayId)
                        UndoResult.Nothing -> ReceiptMutation.Failed
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { _mutation.value = ReceiptMutation.Failed }
        }
    }

    fun mutationHandled() { _mutation.value = null }

    fun backToWorkout() {
        savedState[KEY_FINISH] = null
    }

    fun dismissReceipt(): Boolean {
        if (executionActive() || receipt.value == null) return false
        savedState[KEY_WORKOUT_RECEIPT] = null
        savedState[KEY_RECEIPT_SET_COUNTS] = null
        savedState[KEY_RECEIPT_PROGRESS] = null
        return true
    }

    fun setNote(text: String) {
        val id = receipt.value?.takeUnless { it.cardio }?.sessionId ?: return
        val clean = text.trim().take(NOTE_MAX_LENGTH)
        _noteSave.value = null
        scope.launch {
            try {
                historyRepository.setSessionNote(id, clean)
                receipt.value?.takeIf { it.sessionId == id && !it.cardio }?.let {
                    savedState[KEY_WORKOUT_RECEIPT] = it.copy(note = clean)
                }
                _noteSave.value = NoteSave.Saved(clean)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _noteSave.value = NoteSave.Failed(clean)
            }
        }
    }

    fun noteSaveHandled() {
        _noteSave.value = null
    }

    fun share() {
        val id = receipt.value?.takeUnless { it.cardio }?.sessionId ?: return
        scope.launch {
            shareCardService.buildShareIntent(id)?.let { _pendingShare.value = it }
        }
    }

    fun shareHandled() {
        _pendingShare.value = null
    }

    suspend fun showCardio(
        run: CardioRun,
        standing: Map<CardioRecordKey, CardioSetResult>?,
        session: CardioSessionEntity?,
    ) {
        val title = plans.programFor(run.planId).first().days.firstOrNull { it.id == run.dayId }?.title ?: run.dayId
        val completedAt = session?.completedAt ?: clock.millis()
        val receipt = cardioReceipt(
            requireNotNull(run.sessionId), run.dayId, run.dayIndex, title, run.planName,
            completedAt, session?.seconds, run.block.activity, historyRepository.cardioSetsFor(requireNotNull(run.sessionId)), standing,
        )
        showCardioReceipt(receipt, session?.planId ?: run.planId)
    }

    suspend fun showLoggedCardio(
        session: CardioSessionEntity,
        results: List<CardioSetResult>,
        dayIndex: Int,
        standing: Map<CardioRecordKey, CardioSetResult>?,
    ) {
        val dayId = requireNotNull(session.dayId)
        val title = session.planId?.let { plans.programFor(it).first().days.firstOrNull { day -> day.id == dayId }?.title } ?: dayId
        val activity = CardioActivity.entries.firstOrNull { it.name == session.activity }
        showCardioReceipt(cardioReceipt(
            session.id, dayId, dayIndex, title, session.planName.orEmpty(), session.completedAt,
            session.seconds, activity, results, standing, session.note,
        ), session.planId)
    }

    private suspend fun cardioReceipt(
        sessionId: Long,
        dayId: String,
        dayIndex: Int,
        title: String,
        planName: String,
        completedAt: Long,
        seconds: Int?,
        activity: CardioActivity?,
        results: List<CardioSetResult>,
        standing: Map<CardioRecordKey, CardioSetResult>?,
        note: String = "",
    ): WorkoutReceipt = WorkoutReceipt(
        sessionId = sessionId, cardio = true, dayId = dayId, dayIndex = dayIndex,
        dayTitle = title, planName = planName, completedAt = completedAt,
        minutes = seconds?.let { (it / 60.0).roundToInt().coerceAtLeast(1) },
        unit = settings.unitFlow.first(), recordsChecked = standing != null, allPlansDone = allPlansDone(completedAt),
        groups = emptyList(), activity = activity,
        cardioRows = activity?.let { ReceiptBuilder.cardio(it, results, standing.orEmpty()) }.orEmpty(),
        note = note,
    )

    private fun showCardioReceipt(receipt: WorkoutReceipt, planId: Long?) {
        if (executionActive()) return
        savedState[KEY_RECEIPT_SET_COUNTS] = null
        savedState[KEY_RECEIPT_PROGRESS] = null
        savedState[KEY_WORKOUT_RECEIPT] = receipt
        savedState[KEY_RECEIPT_PROGRESS] = ReceiptProgress(null, receipt.sessionId, planId, receipt.dayId, emptyList())
    }

    private suspend fun allPlansDone(completedAt: Long): Boolean = ReceiptBuilder.allPlansDone(
        plans.plansFlow.first(), historyRepository.sessionsFlow.first(), historyRepository.cardioSessionsFlow.first(),
        completedAt, ZoneId.systemDefault(),
    )

    private companion object {
        const val KEY_FINISH = "day_finish"
        const val KEY_WORKOUT_RECEIPT = "day_workout_receipt"
        const val KEY_RECEIPT_SET_COUNTS = "day_receipt_set_counts"
        const val KEY_RECEIPT_PROGRESS = "day_receipt_progress"
    }
}
