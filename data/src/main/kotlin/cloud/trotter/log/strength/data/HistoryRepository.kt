package cloud.trotter.log.strength.data

import androidx.room.withTransaction
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.CustomExerciseDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.SessionTonnageRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.CardioSetResultEntity
import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.mapping.toDomainOrNull
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.domain.model.NOTE_MAX_LENGTH
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.records.CardioBests
import cloud.trotter.log.strength.domain.records.CardioRecordKey
import cloud.trotter.log.strength.domain.seeding.CarryForward
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRepository(
    private val db: StrengthDatabase,
    private val settings: SettingsStore,
    private val plans: PlanRepository,
    private val sessionDao: SessionDao = db.sessionDao(),
    private val customExerciseDao: CustomExerciseDao = db.customExerciseDao(),
) {
    private val restoreMarkerDao = db.restoreMarkerDao()
    private val cardioSessionDao = db.cardioSessionDao()
    private val cardioSetResultDao = db.cardioSetResultDao()
    private val sessionNotesMigrationMutex = Mutex()

    val sessionNotesFlow: Flow<Map<Long, String>> = flow {
        ensureSessionNotesMoved()
        emitAll(sessionDao.observeSessions().map { sessions ->
            sessions.filter { it.note.isNotBlank() }.associate { it.id to it.note }
        })
    }

    suspend fun setSessionNote(sessionId: Long, text: String) {
        ensureSessionNotesMoved()
        sessionDao.setNote(sessionId, text.trim().take(NOTE_MAX_LENGTH))
    }

    suspend fun ensureSessionNotesMoved() {
        sessionNotesMigrationMutex.withLock {
            if (restoreMarkerDao.nonce() != null) return@withLock
            val notes = settings.legacySessionNotesFlow.first()
            if (notes.isEmpty()) return@withLock
            db.withTransaction {
                notes.forEach { (id, note) -> sessionDao.setNote(id, note.trim().take(NOTE_MAX_LENGTH)) }
            }
            settings.removeLegacySessionNotes()
        }
    }

    val sessionsFlow: Flow<List<WorkoutSessionEntity>> = sessionDao.observeSessions()

    /**
     * Append-only cardio history, newest completion first. Labels are valid only
     * when non-blank and at most [TrackerRepository.CARDIO_LABEL_MAX_LENGTH] characters. A mode is
     * persisted as [CardioMode.name]; readers must use [decodedCardioMode], whose
     * nullable decode keeps an unknown future name readable without pretending
     * it is a known activity mode.
     */
    val cardioSessionsFlow: Flow<List<CardioSessionEntity>> = cardioSessionDao.observeSessions()

    val selectedPlanCardioSessionsFlow: Flow<List<CardioSessionEntity>> =
        combine(plans.selectedPlanIdFlow, cardioSessionsFlow) { planId, sessions ->
            sessions.filter { planId != null && it.planId == planId }
        }

    /** Latest workout or cardio completion; [PlanEntity.LEGACY_ID] also counts null-plan history. */
    val selectedPlanLastSessionAtFlow: Flow<Long?> =
        plans.selectedPlanIdFlow.flatMapLatest { planId ->
            if (planId == null) flowOf(null) else lastSessionAtFor(planId)
        }.distinctUntilChanged()

    /** A fixed plan's latest workout or cardio completion, including legacy history. */
    fun lastSessionAtFor(planId: Long): Flow<Long?> =
        combine(sessionsFlow, cardioSessionsFlow) { sessions, cardio ->
            listOfNotNull(
                sessions.filter { belongsToPlan(it.planId, planId) }.maxOfOrNull { it.completedAt },
                cardio.filter { belongsToPlan(it.planId, planId) }.maxOfOrNull { it.completedAt },
            ).maxOrNull()
        }.distinctUntilChanged()

    suspend fun amendCardioSetDistance(sessionId: Long, position: Int, metres: Int?) {
        require(metres == null || metres > 0)
        db.withTransaction {
            require(cardioSetResultDao.forSession(sessionId).any { it.position == position })
            cardioSetResultDao.amendDistance(sessionId, position, metres, metres?.let { "MANUAL" })
        }
    }

    suspend fun cardioSession(id: Long): CardioSessionEntity? = cardioSessionDao.byId(id)

    val cardioSetResultsFlow: Flow<Map<Long, List<CardioSetResult>>> =
        cardioSetResultDao.observeAll().map { rows ->
            rows.groupBy { it.cardioSessionId }.mapValues { (_, sets) -> sets.mapNotNull { it.toDomainOrNull() } }
        }.distinctUntilChanged()

    suspend fun cardioSetsFor(sessionId: Long): List<CardioSetResult> =
        cardioSetResultDao.forSession(sessionId).mapNotNull { it.toDomainOrNull() }

    suspend fun cardioBestsFor(
        activity: CardioActivity,
        excludedDayId: String? = null,
        excludedStartedAt: Long? = null,
    ): Map<CardioRecordKey, CardioSetResult> {
        val results = cardioSetResultDao.finishedForActivity(
            activity.name, CardioSetStatus.FINISHED.name, excludedDayId, excludedStartedAt,
        ).mapNotNull { it.toDomainOrNull() }
        return CardioBests.bests(activity, results)
    }

    /** Exact rows for undo, including original ids, order and stored specs. */
    data class DeletedCardioSession(
        val session: CardioSessionEntity,
        val sets: List<CardioSetResultEntity>,
    )

    suspend fun deleteCardioSession(sessionId: Long): DeletedCardioSession? = db.withTransaction {
        val session = cardioSessionDao.byId(sessionId) ?: return@withTransaction null
        val sets = cardioSetResultDao.forSession(sessionId)
        cardioSetResultDao.deleteForSession(sessionId)
        cardioSessionDao.deleteById(sessionId)
        DeletedCardioSession(session, sets)
    }

    suspend fun restoreCardioSession(deleted: DeletedCardioSession) {
        db.withTransaction {
            cardioSessionDao.insert(deleted.session)
            if (deleted.sets.isNotEmpty()) cardioSetResultDao.insertAll(deleted.sets)
        }
    }

    /** The Log screen's list: every session newest-first, with its completed
     *  set count pre-aggregated (no per-row query as history grows). */
    val sessionSummariesFlow: Flow<List<SessionSummaryRow>> = sessionDao.observeSessionSummaries()

    /** Selected plan's workout history, including pre-plans history for the migrated plan. */
    val selectedPlanSessionSummariesFlow: Flow<List<SessionSummaryRow>> =
        plans.selectedPlanIdFlow.flatMapLatest { planId ->
            if (planId == null) flowOf(emptyList()) else sessionSummariesFor(planId)
        }

    /** A fixed plan's workout history, including pre-plans history for the migrated plan. */
    fun sessionSummariesFor(planId: Long): Flow<List<SessionSummaryRow>> =
        sessionSummariesFlow.map { sessions ->
            sessions.filter { belongsToPlan(it.session.planId, planId) }
        }

    /** Every session's heaviest completed TOP set per lift, oldest first — the
     *  journal's trajectory series and the cascade ceremony's before-picture. */
    val topSetHistoryFlow: Flow<List<TopSetRow>> = sessionDao.observeTopSets(SetKind.TOP.name)

    /** Completed tonnage per session, oldest first — the journal's weekly bars. */
    val sessionTonnageFlow: Flow<List<SessionTonnageRow>> = sessionDao.observeSessionTonnage()

    /** One session's sets, fetched on demand when the Log screen expands a row — not
     *  part of [sessionSummariesFlow] because most rows stay collapsed. */
    suspend fun sessionSets(sessionId: Long): List<SessionSetEntity> = sessionDao.setsForSession(sessionId)

    /** One session header by id: the Health Connect publish path pairs this
     *  with [sessionSets] to build the exported record. */
    suspend fun session(sessionId: Long): WorkoutSessionEntity? = sessionDao.sessionById(sessionId)

    /** Exact rows removed by [deleteSession], retained briefly by the Log screen
     *  so undo can restore their primary keys and denormalized history verbatim. */
    data class DeletedSession(
        val session: WorkoutSessionEntity,
        val sets: List<SessionSetEntity>,
    )

    /**
     * Corrects one archived set immediately. History edits only change recorded
     * ACTUALs; they never retroactively fire or undo a GOAL cascade.
     */
    suspend fun updateSessionSet(set: SessionSetEntity) {
        require(sessionDao.updateSet(set) == 1) { "Unknown session set ${set.id}" }
    }

    /** Deletes history only; in particular, the DataStore rotation pointer is untouched. */
    suspend fun deleteSession(sessionId: Long): DeletedSession? {
        ensureSessionNotesMoved()
        return db.withTransaction {
            val session = sessionDao.sessionById(sessionId) ?: return@withTransaction null
            val sets = sessionDao.setsForSession(sessionId)
            sessionDao.deleteSetsForSession(sessionId)
            sessionDao.deleteSessionById(sessionId)
            DeletedSession(session, sets)
        }
    }

    /** Restores a just-deleted session exactly, including every original row id. */
    suspend fun restoreSession(deleted: DeletedSession) {
        db.withTransaction {
            sessionDao.insertSessions(listOf(deleted.session))
            if (deleted.sets.isNotEmpty()) sessionDao.insertSets(deleted.sets)
        }
    }

    /** Most recent completed-session date per exercise, for the Lift sheet. */
    suspend fun lastPerformedDates(exerciseIds: List<String>): Map<String, Long> =
        if (exerciseIds.isEmpty()) emptyMap() else sessionDao.lastPerformedDates(exerciseIds)
            .associate { it.exerciseId to it.completedAt }

    /**
     * Batches the A1 "last time" chip for a whole day into one query:
     * [exerciseIds]' most recent completed performance, keyed by exercise id. An
     * id with no history is simply absent from the result.
     */
    suspend fun lastPerformed(exerciseIds: List<String>): Map<String, LastPerformed> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return sessionDao.lastPerformedRows(exerciseIds).toLastPerformedByExercise()
    }

    /** Archived tracks of the newest session for this plan and day, keyed by exercise and slot.
     *  For duplicate keys, the last archived track wins. */
    suspend fun slotSessions(planId: Long, dayId: String): Map<Pair<String, String>, SlotSession> = db.withTransaction {
        val session = sessionDao.lastSessionForDay(planId, dayId) ?: return@withTransaction emptyMap()
        archivedTracks(session, sessionDao.setsForSession(session.id))
    }

    /** Each day's newest tracks in this plan, grouped by lift with newest evidence first. */
    suspend fun progressionSessions(planId: Long, exerciseIds: List<String>): Map<String, List<CarryForward.Session>> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return db.withTransaction {
            val sessions = sessionDao.latestSessionsForPlan(planId)
            if (sessions.isEmpty()) return@withTransaction emptyMap()
            val sets = sessionDao.setsForSessions(sessions.map { it.id }, exerciseIds).groupBy { it.sessionId }
            val result = mutableMapOf<String, MutableList<CarryForward.Session>>()
            for (session in sessions) {
                for ((key, track) in archivedTracks(session, sets[session.id].orEmpty())) {
                    if (key.first !in exerciseIds || track.retired) continue
                    result.getOrPut(key.first) { mutableListOf() }.add(CarryForward.Session(track.planned, track.actual))
                }
            }
            result
        }
    }

    private fun archivedTracks(
        session: WorkoutSessionEntity,
        sets: List<SessionSetEntity>,
    ): Map<Pair<String, String>, SlotSession> {
        val plannedJson = session.plannedJson ?: return emptyMap()
        return SessionPlanJson.decode(plannedJson).associateBy { it.exerciseId to it.slot }.mapValues { (_, dto) ->
            SlotSession(
                dto.sets.map(SetDto::toDomain),
                sets.filter { it.exerciseId == dto.exerciseId && it.slot == dto.slot && it.trackPosition == dto.position }
                    .sortedBy { it.setIndex }.map { it.toLoggedSet() },
                dto.retired,
            )
        }
    }

    suspend fun lastPerformedForSlots(
        planId: Long,
        dayId: String,
        exerciseIds: List<String>,
    ): Map<Pair<String, String>, SlotLast> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return sessionDao.lastPerformedSourceRows(exerciseIds).toLastPerformedForSlots(planId, dayId)
    }

    /**
     * Batches the day-card "Best" chip for a whole day into one query
     * (performance-profile.md Phase 1): [exerciseIds]' all-time heaviest
     * completed performance, keyed by exercise id. An id with no history is
     * simply absent from the result. Purely derived from session history —
     * never stored, never touches GOAL math.
     */
    suspend fun personalRecords(
        exerciseIds: List<String>,
        excludingRunId: String? = null,
    ): Map<String, PersonalRecord> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return sessionDao.personalRecordRows(exerciseIds, excludingRunId).toPersonalRecordsByExercise()
    }

    // --- CSV history export/import --------------------------------------------

    /**
     * Every session + set for CSV history export, in the same deterministic
     * order as [BackupRepository.exportSnapshot]'s equivalent fields. Read-only — `:transfer`
     * builds the CSV text from this instead of touching Room.
     */
    suspend fun exportSessionHistory(): SessionHistorySnapshot = SessionHistorySnapshot(
        unit = settings.unitFlow.first(),
        sessions = sessionDao.allSessions(),
        sessionSets = sessionDao.allSessionSets(),
        cardioSessions = cardioSessionDao.all(),
        cardioSets = cardioSetResultDao.all().groupBy { it.cardioSessionId }.mapNotNull { (id, rows) ->
            val sets = rows.mapNotNull { it.toDomainOrNull() }
            if (sets.size == rows.size) id to sets else null
        }.toMap(),
    )

    /**
     * Appends CSV-imported history in one transaction
     * (docs/briefs/m4-transfer.md, "#16 CSV"), per D9's one staging
     * transaction rule. Additive only: unlike [BackupRepository.importSnapshot]'s full
     * destructive replace, the program and live logs are untouched.
     * [newCustomExercises] are upserted first so every imported set's
     * `exerciseId` already resolves by the time its row lands; each
     * [ImportedSession] then gets a freshly generated session id stamped onto
     * its own sets before they're inserted, exactly as [TrackerRepository.finishRun] links a
     * completed day's sets to the session it just created.
     *
     * The caller has already validated the file and the user has already
     * confirmed the exercise-name matches (`:transfer`'s preview/confirm model)
     * — this method performs no validation of its own and always commits what
     * it's given. That includes a null [WorkoutSessionEntity.bodyweightLb]: a
     * CSV has no bodyweight column, so an imported session legitimately records
     * none, and every reader downstream (backup, Health Connect, the Log screen)
     * is required to handle it.
     */
    suspend fun importSessionHistory(
        sessions: List<ImportedSession>,
        newCustomExercises: List<CustomExerciseEntity>,
        cardioSessions: List<ImportedCardioSession> = emptyList(),
    ) {
        db.withTransaction {
            if (newCustomExercises.isNotEmpty()) customExerciseDao.upsertAll(newCustomExercises)
            sessions.forEach { imported ->
                val sessionId = sessionDao.insertSession(imported.session.copy(id = 0))
                if (imported.sets.isNotEmpty()) {
                    sessionDao.insertSets(imported.sets.map { it.copy(id = 0, sessionId = sessionId) })
                }
            }
            cardioSessions.forEach { imported ->
                val sessionId = cardioSessionDao.insert(imported.session.copy(id = 0))
                if (imported.sets.isNotEmpty()) {
                    cardioSetResultDao.insertAll(imported.sets.mapIndexed { position, set ->
                        set.toEntity(sessionId, position)
                    })
                }
            }
        }
    }

    private fun SessionSetEntity.toLoggedSet(): LoggedSet = LoggedSet(
        weightLb = weightLb,
        reps = reps,
        kind = SetKind.entries.firstOrNull { it.name == kind } ?: SetKind.WORK,
        done = done,
        seconds = seconds,
        startedAtMillis = startedAtMillis,
        completedAtMillis = completedAtMillis,
        plannedIndex = plannedIndex,
    )

}

/** Unknown stored enum names stay unknown: callers may render [mode], while typed consumers skip it. */
fun CardioSessionEntity.decodedCardioMode(): CardioMode? =
    CardioMode.entries.find { it.name == mode }

/** One archived track of the newest session for a plan and day. */
data class SlotSession(val planned: List<LoggedSet>, val actual: List<LoggedSet>, val retired: Boolean)
