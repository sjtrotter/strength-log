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
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toDomainOrNull
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.NOTE_MAX_LENGTH
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.FinishedRule
import cloud.trotter.log.strength.domain.records.CardioBests
import cloud.trotter.log.strength.domain.records.CardioRecordKey
import cloud.trotter.log.strength.domain.records.PersonalBests
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
     * Cardio history, newest completion first. Labels are valid only
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

    /** [readingAtEnd] is the user's reading at a planned end the set passed ([FinishedRule.withReading]); it is
     *  ignored when the row awaits none, so it never overwrites one.
     *  An unchanged distance keeps its stored source. */
    internal suspend fun amendCardioSetDistance(sessionId: Long, position: Int, metres: Int?, readingAtEnd: Int? = null) {
        require(metres == null || metres > 0)
        db.withTransaction {
            val row = requireNotNull(cardioSetResultDao.forSession(sessionId).firstOrNull { it.position == position })
            val amended = row.copy(
                metres = metres,
                distanceSource = if (metres == row.metres) row.distanceSource else metres?.let { DistanceSource.MANUAL.name },
            ).withReadingsAtEnd(row)
            val supplied = readingAtEnd?.let { reading -> amended.toDomainOrNull()?.let { FinishedRule.withReading(it, reading) } }
            cardioSetResultDao.update(supplied?.let {
                amended.copy(status = it.status.name, metresAtEnd = it.metresAtEnd, secondsAtEnd = it.secondsAtEnd,
                    readingSource = it.readingSource?.name)
            } ?: amended)
            if (supplied != null && supplied.status.name != row.status) {
                cardioSessionDao.byId(sessionId)?.let { cardioSessionDao.update(it.copy(stepsCompleted = it.stepsCompleted + 1)) }
            }
        }
    }

    /** Supplied readings and unreadable rows keep their readings when totals change. */
    private fun CardioSetResultEntity.withReadingsAtEnd(previous: CardioSetResultEntity): CardioSetResultEntity {
        val before = previous.toDomainOrNull() ?: return this
        val edited = toDomainOrNull() ?: return this
        val readings = FinishedRule.afterEdit(before, edited)
        return copy(metresAtEnd = readings.metresAtEnd, secondsAtEnd = readings.secondsAtEnd,
            readingSource = readings.readingSource?.name)
    }

    suspend fun cardioSession(id: Long): CardioSessionEntity? = cardioSessionDao.byId(id)

    /** Single-set edits share [CardioEdit] validation and keep the stored source when distance is unchanged. */
    suspend fun updateCardioSession(
        id: Long,
        activity: CardioActivity,
        seconds: Int?,
        metres: Int?,
        note: String,
        readingAtEnd: Int? = null,
    ) {
        db.withTransaction {
            val session = cardioSessionDao.byId(id) ?: return@withTransaction
            val results = cardioSetResultDao.forSession(id)
            val summary = ManualCardio.summary(activity, session.seconds)
            val editedSeconds = seconds?.takeIf { results.size == 1 }
            if (editedSeconds != null) {
                val result = results.single()
                val edited = requireNotNull(CardioEdit.apply(result.toDomain(), editedSeconds, metres, readingAtEnd))
                if (editedSeconds != result.elapsedSeconds || metres != result.metres || readingAtEnd != null) {
                    cardioSetResultDao.update(edited.toEntity(id, result.position).copy(id = result.id))
                }
            }
            cardioSessionDao.update(session.copy(
                activity = activity.name,
                mode = summary.mode.name,
                label = summary.label,
                completedAt = editedSeconds?.let { session.startedAt + it * 1_000L } ?: session.completedAt,
                seconds = editedSeconds ?: session.seconds,
                note = note.trim().take(NOTE_MAX_LENGTH),
            ))
        }
    }

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

    suspend fun deleteCardioSession(sessionId: Long): DeletedCardio? = db.withTransaction {
        val session = cardioSessionDao.byId(sessionId) ?: return@withTransaction null
        val sets = cardioSetResultDao.forSession(sessionId)
        cardioSetResultDao.deleteForSession(sessionId)
        cardioSessionDao.deleteById(sessionId)
        DeletedCardio(session, sets)
    }

    suspend fun restoreCardioSession(deleted: DeletedCardio) {
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

    /** Every session's heaviest set counted by PersonalBests per lift, oldest first. */
    val topSetHistoryFlow: Flow<List<TopSetRow>> = sessionDao.observeTrajectorySets().map { rows ->
        rows.filter { row ->
            SetKind.entries.any { it.name == row.set.kind } && PersonalBests.counts(row.set.toLoggedSet())
        }.groupBy { it.set.sessionId to it.set.exerciseId }.map { (key, sets) ->
            TopSetRow(key.first, sets.first().completedAt, key.second, sets.maxOf { it.set.weightLb })
        }
    }

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

    /** Exact rows for undo, including original ids, order and stored specs. */
    data class DeletedCardio(
        val session: CardioSessionEntity,
        val sets: List<CardioSetResultEntity>,
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

    /** Every done working set from each exercise's most recent session, in logged order. */
    suspend fun lastSets(exerciseIds: List<String>): Map<String, List<LoggedSet>> =
        if (exerciseIds.isEmpty()) emptyMap() else sessionDao.lastPerformedSourceRows(exerciseIds).toLastSetsByExercise()

    /** Offers belong to a program slot, including when an exercise appears twice on the same day. */
    suspend fun slotSessionsById(planId: Long, dayId: String): Map<Pair<Long, String>, SlotSession> = db.withTransaction {
        val session = sessionDao.lastSessionForDay(planId, dayId) ?: return@withTransaction emptyMap()
        val slots = db.programDao().exercisesForDay(planId, dayId)
        archivedTracks(session, sessionDao.setsForSession(session.id)).entries.mapNotNull { (dto, track) ->
            val owner = slots.singleOrNull { slot ->
                (if (dto.programExerciseId != null) slot.id == dto.programExerciseId else slot.position == dto.position) &&
                    when (dto.slot) {
                        Slot.MAIN -> slot.exerciseId
                        Slot.SS -> slot.supersetExerciseId
                        else -> null
                    } == dto.exerciseId
            } ?: return@mapNotNull null
            (owner.id to dto.slot) to if (dto.programExerciseId == null) track.copy(offer = null) else track
        }.toMap()
    }

    private fun archivedTracks(
        session: WorkoutSessionEntity,
        sets: List<SessionSetEntity>,
    ): Map<PlannedTrackDto, SlotSession> {
        val plannedJson = session.plannedJson ?: return emptyMap()
        return SessionPlanJson.decode(plannedJson).associateBy { it }.mapValues { (_, dto) ->
            SlotSession(
                dto.sets.map(SetDto::toDomain),
                sets.filter { it.exerciseId == dto.exerciseId && it.slot == dto.slot && it.trackPosition == dto.position }
                    .sortedBy { it.setIndex }.map { it.toLoggedSet() },
                dto.retired,
                dto.offer?.let { offer ->
                    val beforeRule = offer.beforeRuleJson?.let(SlotRuleJson::decode)
                    val before = beforeRule?.target
                    val after = offer.afterRuleJson?.let(SlotRuleJson::decode)?.target
                    NextOffer(offer.fromLb, offer.toLb,
                        before?.reps.takeIf { before != after }, after?.reps.takeIf { before != after },
                        beforeRule?.state?.calibrating == true || dto.opening?.layoff == OpeningDto.RECALIBRATE)
                },
                dto.opening != null,
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
data class SlotSession(val planned: List<LoggedSet>, val actual: List<LoggedSet>, val retired: Boolean, val offer: NextOffer? = null, val opened: Boolean = false)

/** The next-time change recorded on an archived track. */
data class NextOffer(val fromLb: Double, val toLb: Double, val fromReps: Int? = null, val toReps: Int? = null, val calibration: Boolean = false)
