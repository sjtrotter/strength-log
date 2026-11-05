package cloud.trotter.log.strength.ui.day

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.HistoryRepository
import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.records.CardioRecordKey
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.receipt.FinishAttempt
import cloud.trotter.log.strength.ui.day.receipt.NoteSave
import cloud.trotter.log.strength.ui.day.receipt.ReceiptBuilder
import cloud.trotter.log.strength.ui.day.receipt.WorkoutReceipt
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.time.Clock
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val loggedCardio: suspend (planId: Long, dayId: String) -> CardioSessionEntity?,
) {
    val attempt = savedState.getStateFlow<FinishAttempt?>(KEY_FINISH, null)
    val receipt = savedState.getStateFlow<WorkoutReceipt?>(KEY_WORKOUT_RECEIPT, null)
    private val _noteSave = MutableStateFlow<NoteSave?>(null)
    val noteSave: StateFlow<NoteSave?> = _noteSave.asStateFlow()
    private val _pendingShare = MutableStateFlow<Intent?>(null)
    val pendingShare: StateFlow<Intent?> = _pendingShare.asStateFlow()
    private var job: Job? = null

    init {
        savedState.remove<SessionReceipt>("day_session_receipt")
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
        // Gone or already finished (a retry or restore after the commit landed): nothing left to do.
        if (run == null) {
            savedState[KEY_FINISH] = null
            return
        }
        val current = attempt.value ?: FinishAttempt(
            runId, planId, dayId, dayIndex, dayTitle,
            plans.plansFlow.first().firstOrNull { it.id == planId }?.name.orEmpty(),
            run.startedAt, clock.millis(),
        ).also { savedState[KEY_FINISH] = it }
        val cardio = loggedCardio(planId, dayId)
        if (cardio == null && run.tracks.none { track -> track.sets.any { it.done } }) {
            savedState[KEY_FINISH] = null
            return
        }
        val slots = plans.slotsFor(planId, dayId).first()
        val exercises = catalog.catalogFlow.first()
        // DataStore reads must finish before finishRun opens its Room transaction.
        val unit = settings.unitFlow.first()
        val program = plans.programFor(planId).first()
        val ids = run.tracks.map { it.exerciseId }.distinct()
        var standing = baseline(ids, runId)
        val finished = repo.finishRunCapturing(runId) ?: run {
            savedState[KEY_FINISH] = null
            return
        }
        val sessionId = finished.sessionId
        // Publish as soon as the commit lands, so a failed receipt read never skips Health Connect.
        // Null tracks: an earlier finish archived this run and published it.
        if (finished.tracks != null) scope.launch { sessionPublisher.publish(sessionId) }
        // Describe what the transaction archived, not the earlier read: a watch edit can land in between.
        val tracks = finished.tracks ?: run.tracks
        val swappedIn = tracks.map { it.exerciseId }.distinct() - ids.toSet()
        if (standing != null && swappedIn.isNotEmpty()) {
            standing = baseline(swappedIn, runId)?.let { standing.orEmpty() + it }
        }
        val groups = ReceiptBuilder.strength(tracks, slots, { exercises.find(it)?.name ?: it }, standing.orEmpty())
        val cardioSets = cardio?.let { historyRepository.cardioSetsFor(it.id) }.orEmpty()
        val activity = cardio?.activity?.let { name -> CardioActivity.entries.firstOrNull { it.name == name } }
        val cardioStanding = if (cardio != null && activity != null) cardioBaseline(activity, dayId, cardio.startedAt) else null
        val cardioRows = if (activity != null) ReceiptBuilder.cardio(activity, cardioSets, cardioStanding.orEmpty()) else emptyList()
        val nextDayId = plans.plansFlow.first().firstOrNull { it.id == planId }?.suggestedDayId
        afterCommit(dayId, nextDayId)
        val session = historyRepository.session(sessionId)
        val completedAt = session?.completedAt ?: clock.millis()
        val allPlansDone = allPlansDone(completedAt)
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

    fun backToWorkout() {
        savedState[KEY_FINISH] = null
    }

    fun dismissReceipt(): Boolean {
        if (executionActive() || receipt.value == null) return false
        savedState[KEY_WORKOUT_RECEIPT] = null
        return true
    }

    fun setNote(text: String) {
        val id = receipt.value?.takeUnless { it.cardio }?.sessionId ?: return
        val clean = text.trim().take(120)
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
        val unit = settings.unitFlow.first()
        val allPlansDone = allPlansDone(completedAt)
        if (executionActive()) return
        savedState[KEY_WORKOUT_RECEIPT] = WorkoutReceipt(
            sessionId = requireNotNull(run.sessionId), cardio = true, dayId = run.dayId, dayIndex = run.dayIndex,
            dayTitle = title, planName = run.planName, completedAt = completedAt,
            minutes = session?.seconds?.let { (it / 60.0).roundToInt().coerceAtLeast(1) },
            unit = unit, recordsChecked = standing != null, allPlansDone = allPlansDone,
            groups = emptyList(), activity = run.block.activity,
            cardioRows = ReceiptBuilder.cardio(run.block.activity, run.resultSets, standing.orEmpty()),
        )
    }

    private suspend fun allPlansDone(completedAt: Long): Boolean = ReceiptBuilder.allPlansDone(
        plans.plansFlow.first(), historyRepository.sessionsFlow.first(), historyRepository.cardioSessionsFlow.first(),
        completedAt, ZoneId.systemDefault(),
    )

    private companion object {
        const val KEY_FINISH = "day_finish"
        const val KEY_WORKOUT_RECEIPT = "day_workout_receipt"
    }
}
