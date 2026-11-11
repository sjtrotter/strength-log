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
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.NOTE_MAX_LENGTH
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.records.CardioRecordKey
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.receipt.FinishAttempt
import cloud.trotter.log.strength.ui.day.receipt.NoteSave
import cloud.trotter.log.strength.ui.day.receipt.ReceiptBuilder
import cloud.trotter.log.strength.ui.day.receipt.ReceiptNextTime
import cloud.trotter.log.strength.ui.day.receipt.ReceiptRepNextTime
import cloud.trotter.log.strength.ui.day.receipt.ReceiptRepNextTimes
import cloud.trotter.log.strength.ui.day.receipt.ReceiptNextTimes
import cloud.trotter.log.strength.ui.day.receipt.ReceiptRow
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSetCounts
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
    val receiptSetCounts = savedState.getStateFlow<ReceiptSetCounts?>(KEY_RECEIPT_SET_COUNTS, null)
    val receiptNextTimes = savedState.getStateFlow<ReceiptNextTimes?>(KEY_RECEIPT_NEXT_TIMES, null)
    val receiptRepNextTimes = savedState.getStateFlow<ReceiptRepNextTimes?>(KEY_RECEIPT_REP_NEXT_TIMES, null)
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
        val slots = finished.slots ?: plans.slotsFor(planId, dayId).first()
        val swappedIn = tracks.map { it.exerciseId }.distinct() - ids.toSet()
        if (standing != null && swappedIn.isNotEmpty()) {
            standing = baseline(swappedIn, runId)?.let { standing.orEmpty() + it }
        }
        val groupsWithTracks = ReceiptBuilder.strengthWithTracks(tracks, slots, { exercises.find(it)?.name ?: it }, standing.orEmpty())
        val repNextTimes = mutableListOf<ReceiptRepNextTime>()
        val nextTimes = try {
            val offersOn = plans.plansFlow.first().firstOrNull { it.id == planId }?.offerStrengthIncreases ?: true
            val working = repo.dayTracksFlow(planId, dayId).first().tracks
            val history = historyRepository.slotSessionsById(planId, dayId)
            val items = buildList {
                for ((g, pair) in groupsWithTracks.withIndex()) {
                    val (group, rowTracks) = pair
                    for ((r, track) in rowTracks.withIndex()) {
                        if (track.retired || group.rows[r].shape == ReceiptRow.Shape.SKIPPED) continue
                        val slot = slots.firstOrNull { it.programExerciseId == track.programExerciseId } ?: continue
                        val exerciseId = when (track.slot) {
                            Slot.MAIN -> slot.exercise.exerciseId
                            Slot.SS -> slot.exercise.superset?.exerciseId
                            else -> null
                        }
                        if (track.exerciseId != exerciseId) continue
                        val rows = working.firstOrNull {
                            it.programExerciseId == track.programExerciseId && it.slot == track.slot
                        }?.sets ?: continue
                        when (val next = DayScreenBuilder.nextTime(
                            rows, history[slot.programExerciseId to track.slot], slot.exercise.keepToPlan, unit, offersOn = offersOn,
                        )) {
                            is NextTime.Offer -> if (next.reps != null) {
                                repNextTimes.add(ReceiptRepNextTime(g, r, next.reps))
                            } else if (!next.calibration) add(ReceiptNextTime(g, r, next.offerLb, next.openingLb))
                            NextTime.Repeat -> add(ReceiptNextTime(g, r, null, WorkingRows.workingWeight(rows) ?: 0.0))
                            null -> Unit
                        }
                    }
                }
            }
            ReceiptNextTimes(sessionId, items)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val groups = groupsWithTracks.map { it.first }
        val counts = tracks.setCounts()
        val cardioSets = cardio?.let { historyRepository.cardioSetsFor(it.id) }.orEmpty()
        val activity = cardio?.activity?.let { name -> CardioActivity.entries.firstOrNull { it.name == name } }
        val cardioStanding = if (cardio != null && activity != null) cardioBaseline(activity, dayId, cardio.startedAt) else null
        val cardioRows = if (activity != null) ReceiptBuilder.cardio(activity, cardioSets, cardioStanding.orEmpty()) else emptyList()
        val nextDayId = plans.plansFlow.first().firstOrNull { it.id == planId }?.suggestedDayId
        afterCommit(dayId, nextDayId)
        val session = historyRepository.session(sessionId)
        val completedAt = session?.completedAt ?: clock.millis()
        val allPlansDone = allPlansDone(completedAt)
        savedState[KEY_RECEIPT_SET_COUNTS] = ReceiptSetCounts(sessionId, counts.total, counts.remaining)
        savedState[KEY_RECEIPT_NEXT_TIMES] = nextTimes
        savedState[KEY_RECEIPT_REP_NEXT_TIMES] = ReceiptRepNextTimes(sessionId, repNextTimes)
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
        savedState[KEY_RECEIPT_SET_COUNTS] = null
        savedState[KEY_RECEIPT_NEXT_TIMES] = null
        savedState[KEY_RECEIPT_REP_NEXT_TIMES] = null
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
        showCardioReceipt(receipt)
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
        ))
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

    private fun showCardioReceipt(receipt: WorkoutReceipt) {
        if (executionActive()) return
        savedState[KEY_RECEIPT_SET_COUNTS] = null
        savedState[KEY_RECEIPT_NEXT_TIMES] = null
        savedState[KEY_RECEIPT_REP_NEXT_TIMES] = null
        savedState[KEY_WORKOUT_RECEIPT] = receipt
    }

    private suspend fun allPlansDone(completedAt: Long): Boolean = ReceiptBuilder.allPlansDone(
        plans.plansFlow.first(), historyRepository.sessionsFlow.first(), historyRepository.cardioSessionsFlow.first(),
        completedAt, ZoneId.systemDefault(),
    )

    private companion object {
        const val KEY_FINISH = "day_finish"
        const val KEY_WORKOUT_RECEIPT = "day_workout_receipt"
        const val KEY_RECEIPT_SET_COUNTS = "day_receipt_set_counts"
        const val KEY_RECEIPT_REP_NEXT_TIMES = "day_receipt_rep_next_times"
        const val KEY_RECEIPT_NEXT_TIMES = "day_receipt_next_times"
    }
}
