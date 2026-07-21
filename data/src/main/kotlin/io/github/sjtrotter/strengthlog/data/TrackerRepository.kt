package io.github.sjtrotter.strengthlog.data

import androidx.room.withTransaction
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.checkmark.CheckmarkReset
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.dao.CustomExerciseDao
import io.github.sjtrotter.strengthlog.data.db.dao.ProgramDao
import io.github.sjtrotter.strengthlog.data.db.dao.SessionDao
import io.github.sjtrotter.strengthlog.data.db.dao.SessionSummaryRow
import io.github.sjtrotter.strengthlog.data.db.entity.CustomExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ExerciseLogEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramDayEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.data.mapping.toDomain
import io.github.sjtrotter.strengthlog.data.mapping.toEntity
import io.github.sjtrotter.strengthlog.data.mapping.toEntry
import io.github.sjtrotter.strengthlog.data.migration.reinterpretRepsAsSeconds
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.data.serialization.SetJson
import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.Rotation
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.standards.RestCategory
import io.github.sjtrotter.strengthlog.domain.standards.RestSettings
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The single data-layer entry point (spec §7 surface, extended by PLAN.md
 * A1/A4/A6). Every read is a [Flow] off Room/DataStore; every mutation is a
 * suspend call that commits immediately, so no working truth ever lives only in
 * memory. Cross-table mutations run in one Room transaction.
 *
 * "Today" for the daily checkmark reset is the device-local date via [clock]
 * (injectable for tests).
 *
 * `open` only to allow a recording subclass in tests to assert the ordering of
 * cross-store mutations (e.g. wizard finish writes the program before the
 * completion flag); the public surface is unchanged.
 */
open class TrackerRepository(
    private val db: StrengthDatabase,
    private val programDao: ProgramDao,
    private val sessionDao: SessionDao,
    private val customExerciseDao: CustomExerciseDao,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    // --- config & preferences ------------------------------------------------

    val configFlow: Flow<LifterConfig> = settings.configFlow
    val cardioPrefsFlow: Flow<CardioPrefs> = settings.cardioPrefsFlow
    val unitFlow: Flow<WeightUnit> = settings.unitFlow
    val restSettingsFlow: Flow<RestSettings> = settings.restSettingsFlow
    val wizardCompleteFlow: Flow<Boolean> = settings.wizardCompleteFlow
    val wizardAnswersFlow: Flow<WizardAnswers> = settings.wizardAnswersFlow

    /** The in-progress session's start stamp (session-start capture), or `null`
     *  between an advance/clear and the next performed tick. A stamp whose stored
     *  calendar date isn't today reads as `null`: it belonged to a session
     *  abandoned on an earlier day (ticked, never advanced), and must never be
     *  inherited by today's — the same cross-day staleness rule [CheckmarkReset]
     *  applies to checkmarks, off the one injectable [clock] (SSOT). */
    val sessionStartedAtFlow: Flow<Long?> = settings.sessionStartRawFlow.map { stamp ->
        stamp?.takeIf { it.date == CheckmarkReset.today(clock) }?.startedAtMillis
    }

    // Block bodies, not expression form: DataStore's Preferences (SettingsStore's
    // setter return type) must not leak into this public surface via inference —
    // it is a :data-internal detail and consumers don't compile against DataStore.
    suspend fun setConfig(config: LifterConfig) {
        settings.setConfig(config)
    }

    suspend fun setCardioPrefs(prefs: CardioPrefs) {
        settings.setCardioPrefs(prefs)
    }

    suspend fun setUnit(unit: WeightUnit) {
        settings.setUnit(unit)
    }

    /** Flips the master rest-timer gate (Setup's "Rest timer on watch" toggle). */
    suspend fun setRestTimerEnabled(enabled: Boolean) {
        settings.setRestTimerEnabled(enabled)
    }

    /** Writes one per-category rest override from the Setup editor; clamping to
     *  [io.github.sjtrotter.strengthlog.domain.standards.RestPolicy]'s bounds is
     *  [SettingsStore]'s job (SSOT). */
    suspend fun setRestOverride(category: RestCategory, seconds: Int) {
        settings.setRestOverride(category, seconds)
    }

    /** The Setup "RESET DEFAULTS" affordance: reverts every rest category to its
     *  RestPolicy default. */
    suspend fun clearRestOverrides() {
        settings.clearRestOverrides()
    }

    open suspend fun setWizardComplete(complete: Boolean) {
        settings.setWizardComplete(complete)
    }

    /** Persists the wizard inputs so a single day can later be regenerated
     *  ([resetDayToTemplate]) and the setup screen can re-run the wizard. */
    open suspend fun setWizardAnswers(answers: WizardAnswers) {
        settings.setWizardAnswers(answers)
    }

    // --- exercise catalog (code + custom overlay) ----------------------------

    val catalogFlow: Flow<ExerciseCatalog> =
        customExerciseDao.observeAll().map { rows -> ExerciseCatalog(rows.map { it.toEntry() }) }

    /**
     * [tracking] selects which of [goalStartLb]/[targetReps]/[targetSeconds] is
     * the live GOAL (mirrors [io.github.sjtrotter.strengthlog.data.mapping.toEntry]):
     * WEIGHTED reads [goalStartLb] as the flat starting weight; REPS reads
     * [targetReps]; TIMED reads [targetSeconds] plus [goalStartLb] as any
     * optional added load. Defaulted so every pre-tracking-types call site
     * (weighted-only) keeps compiling unchanged.
     */
    suspend fun addCustomExercise(
        name: String,
        pattern: MovementPattern,
        equipment: List<Equipment>,
        perHand: Boolean,
        goalStartLb: Double,
        tracking: TrackingType = TrackingType.WEIGHTED,
        targetReps: Int? = null,
        targetSeconds: Int? = null,
    ): String {
        val id = ExerciseCatalog.CUSTOM_ID_PREFIX + UUID.randomUUID().toString().replace("-", "")
        customExerciseDao.upsert(
            CustomExerciseEntity(
                id = id,
                name = name.trim(),
                pattern = pattern.name,
                equipmentCsv = equipment.joinToString(",") { it.name },
                perHand = perHand,
                goalStartLb = goalStartLb,
                tracking = tracking.name,
                targetReps = targetReps,
                targetSeconds = targetSeconds,
            ),
        )
        return id
    }

    suspend fun removeCustomExercise(id: String) = customExerciseDao.delete(id)

    // --- program -------------------------------------------------------------

    val programFlow: Flow<Program> =
        combine(programDao.observeDays(), programDao.observeExercises(), ::assemble)

    /** Full replace with the wizard's generated program (spec §7). Also points the
     *  rotation at the first day. Destructive: old program and live logs are
     *  cleared (history in `workout_session` is untouched). */
    open suspend fun replaceProgram(program: Program) {
        db.withTransaction {
            programDao.deleteAllLogs()
            programDao.deleteAllExercises()
            programDao.deleteAllDays()
            program.days.forEachIndexed { index, day ->
                programDao.upsertDay(day.toEntity().copy(position = index))
                day.exercises.forEachIndexed { pos, pe ->
                    programDao.insertExercise(pe.toEntity(day.id, pos))
                }
            }
        }
        program.days.firstOrNull()?.let { settings.setSuggestedDay(it.id) }
    }

    /** Swaps the exercise in a slot (spec §8.3). Keeps the slot's stable id but
     *  clears its live log so the new exercise reseeds from its own GOAL; prior
     *  performance survives in `workout_session` history. */
    suspend fun swapExercise(dayId: String, position: Int, newExerciseId: String) {
        db.withTransaction {
            val row = programDao.exerciseAt(dayId, position) ?: return@withTransaction
            programDao.setExerciseId(row.id, newExerciseId)
            programDao.deleteLogsForExercise(dayId, row.id)
        }
    }

    /** Appends an exercise to the end of a day (spec §8.3). */
    suspend fun addExercise(dayId: String, exercise: ProgramExercise) {
        db.withTransaction {
            val position = programDao.maxPosition(dayId) + 1
            programDao.insertExercise(exercise.toEntity(dayId, position))
        }
    }

    /** Removes the exercise at [position] and its live log (spec §8.3). The
     *  min-exercises-per-day rule is a UI concern and is not enforced here. */
    suspend fun removeExercise(dayId: String, position: Int) {
        db.withTransaction {
            val row = programDao.exerciseAt(dayId, position) ?: return@withTransaction
            programDao.deleteLogsForExercise(dayId, row.id)
            programDao.deleteExercise(row.id)
        }
    }

    /** Regenerates one day from the stored wizard answers (spec §8.3), leaving the
     *  other days untouched. No-op if answers regenerate no such day. */
    suspend fun resetDayToTemplate(dayId: String) {
        val answers = settings.wizardAnswersFlow.first()
        val regenerated = ProgramGenerator.generate(answers).program
        val newDay = regenerated.days.firstOrNull { it.id == dayId } ?: return
        val position = programDao.day(dayId)?.position
            ?: regenerated.days.indexOfFirst { it.id == dayId }
        db.withTransaction {
            programDao.deleteLogsForDay(dayId)
            programDao.deleteExercisesForDay(dayId)
            programDao.upsertDay(newDay.toEntity().copy(position = position))
            newDay.exercises.forEachIndexed { pos, pe ->
                programDao.insertExercise(pe.toEntity(dayId, pos))
            }
        }
    }

    // --- one-shot legacy fixups ----------------------------------------------

    /**
     * One-shot reps→seconds carry for live logs of entries reclassified to TIMED
     * (tracking-types P3, Decision 5). Before the update those exercises (plank,
     * hollow hold, weighted plank, suitcase carry) were tracked with the only
     * field the UI offered — reps — so a timed hold was recorded there; this moves
     * it into `seconds` and zeroes reps for exactly those slots. It never touches
     * any other exercise and never deletes a row.
     *
     * One-shot on two fronts: a DataStore flag short-circuits it after the first
     * run, and the underlying [reinterpretRepsAsSeconds] only moves an *unfixed*
     * set (seconds 0, reps > 0), so even a second run — e.g. after the flag is
     * cleared by a restore — leaves already-migrated holds untouched. Runs in one
     * transaction so a crash mid-fixup leaves every slot either fully old or fully
     * carried, never half. Called once at app startup.
     */
    suspend fun runLegacyTimedFixupIfNeeded() {
        if (settings.legacyTimedFixupDoneFlow.first()) return
        db.withTransaction {
            val slotsById = programDao.allExercises().associateBy { it.id }
            for (log in programDao.allLogs()) {
                val pe = slotsById[log.programExerciseId] ?: continue
                val exerciseId = if (log.slot == Slot.SS) pe.supersetExerciseId else pe.exerciseId
                if (exerciseId == null || exerciseId !in ExerciseLibrary.RECLASSIFIED_TO_TIMED_IDS) continue
                val original = SetJson.decodeSets(log.setsJson)
                val fixed = original.map { it.reinterpretRepsAsSeconds() }
                if (fixed != original) {
                    programDao.upsertLog(log.copy(setsJson = SetJson.encodeSets(fixed)))
                }
            }
        }
        settings.setLegacyTimedFixupDone()
    }

    // --- live logs -----------------------------------------------------------

    /**
     * The day's exercise slots with their stable row ids, in program order. The UI
     * needs the id to key and seed each slot's log ([logFlow]/[updateSets]); the
     * pure-domain [Program] from [programFlow] deliberately doesn't carry it.
     */
    fun daySlotsFlow(dayId: String): Flow<List<ProgramSlot>> =
        programDao.observeExercisesForDay(dayId).map { rows ->
            rows.map { ProgramSlot(it.id, it.position, it.toDomain()) }
        }

    /** The day's live logs with the daily checkmark reset applied (spec §7). */
    fun logFlow(dayId: String): Flow<List<LoggedSlot>> =
        programDao.observeLogs(dayId).map { rows ->
            val today = CheckmarkReset.today(clock)
            rows.map { it.toLoggedSlot(today) }
        }

    /** Persists a slot's set track immediately (spec §7). The write stamps today's
     *  date, so the `done` flags it carries are "today's" checks. */
    suspend fun updateSets(dayId: String, programExerciseId: Long, slot: String, sets: List<LoggedSet>) {
        programDao.upsertLog(logEntity(dayId, programExerciseId, slot, sets))
    }

    /**
     * Persists a superset slot's two tracks in one transaction (spec §4/§8.2:
     * rounds stay aligned row-for-row). Paired mutations must never be two
     * separate writes — process death between them would misalign the tracks
     * permanently, and the misalignment would flow into A1 session history.
     */
    suspend fun updateSetsPaired(
        dayId: String,
        programExerciseId: Long,
        mainSets: List<LoggedSet>,
        ssSets: List<LoggedSet>,
    ) {
        db.withTransaction {
            programDao.upsertLog(logEntity(dayId, programExerciseId, Slot.MAIN, mainSets))
            programDao.upsertLog(logEntity(dayId, programExerciseId, Slot.SS, ssSets))
        }
    }

    private fun logEntity(
        dayId: String,
        programExerciseId: Long,
        slot: String,
        sets: List<LoggedSet>,
    ): ExerciseLogEntity =
        ExerciseLogEntity(
            dayId = dayId,
            programExerciseId = programExerciseId,
            slot = slot,
            setsJson = SetJson.encodeSets(sets),
            checkDate = CheckmarkReset.today(clock),
            updatedAt = clock.millis(),
        )

    /**
     * Clears today's checkmarks for one day without advancing the rotation (spec
     * §8.2 footer "clear today's checkmarks"). Invalidates each log's checkDate so
     * the daily-reset rule surfaces every set as unchecked; weights and reps stay.
     * Also clears the session-start stamp (restart semantics, session-start
     * capture): the next tick starts timing a fresh session.
     */
    suspend fun clearChecks(dayId: String) {
        programDao.clearChecksForDay(dayId)
        settings.clearSessionStartedAt()
    }

    /**
     * Stamps "now" as the in-progress session's start — a no-op if already
     * stamped *today* since the last advance/clear (session-start capture); a
     * stamp left over from a previous calendar day is overwritten, not kept.
     * Ticking a set is performing, not planning, so this is called from the day
     * screen's first done=true tick and from the watch delta applier's first
     * done=true apply; both go through this one helper so "session start" means
     * the same thing regardless of which device performed the first set. "Today"
     * is [CheckmarkReset]'s device-local date off [clock] — the one date basis
     * the daily reset already uses.
     */
    suspend fun stampSessionStartIfUnset() =
        settings.stampSessionStartIfUnset(clock.millis(), CheckmarkReset.today(clock))

    // --- rotation & session history ------------------------------------------

    val suggestedDayFlow: Flow<String?> = settings.suggestedDayFlow

    val sessionsFlow: Flow<List<WorkoutSessionEntity>> = sessionDao.observeSessions()

    /** The Log screen's list (#14): every session newest-first, with its total
     *  set count pre-aggregated (no per-row query as history grows). */
    val sessionSummariesFlow: Flow<List<SessionSummaryRow>> = sessionDao.observeSessionSummaries()

    /** One session's sets, fetched on demand when the Log screen expands a row
     *  (#14) — not part of [sessionSummariesFlow] because most rows stay collapsed. */
    suspend fun sessionSets(sessionId: Long): List<SessionSetEntity> = sessionDao.setsForSession(sessionId)

    /** One session header by id (#17): the Health Connect publish path pairs this
     *  with [sessionSets] to build the exported record. */
    suspend fun session(sessionId: Long): WorkoutSessionEntity? = sessionDao.sessionById(sessionId)

    /**
     * Batches the A1 "last time" chip for a whole day into one query (#14):
     * [exerciseIds]' most recent completed performance, keyed by exercise id. An
     * id with no history is simply absent from the result.
     */
    suspend fun lastPerformed(exerciseIds: List<String>): Map<String, LastPerformed> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return sessionDao.lastPerformedRows(exerciseIds).toLastPerformedByExercise()
    }

    /**
     * Batches the day-card "Best" chip for a whole day into one query
     * (performance-profile.md Phase 1): [exerciseIds]' all-time heaviest
     * completed performance, keyed by exercise id. An id with no history is
     * simply absent from the result. Purely derived from session history —
     * never stored, never touches GOAL math.
     */
    suspend fun personalRecords(exerciseIds: List<String>): Map<String, PersonalRecord> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return sessionDao.personalRecordRows(exerciseIds).toPersonalRecordsByExercise()
    }

    /**
     * "DONE — advance" (spec §7, PLAN.md A1): appends an immutable session record
     * for the completed day (denormalizing exercise names so history survives
     * later edits/deletions), clears that day's checkmarks, and advances the
     * rotation pointer to the following day.
     *
     * Takes the completed day id because the user may have completed a manually
     * overridden day, not the suggested one; the spec's bare `advanceDay()` can't
     * express that.
     *
     * Returns the id of the session row it just appended, so the caller can hand
     * it to a [SessionPublisher] (#17, D7 trigger point) without re-querying for
     * "the latest session" and racing a second completion.
     *
     * Session-start stamp: the in-progress-session start lives in DataStore, not
     * Room ([stampSessionStartIfUnset]), so consuming it here spans two stores
     * with no shared transaction. The stamp is read *before* the Room write and
     * cleared *after* it commits — a crash before the read changes nothing; a
     * crash between the read and the commit leaves the stamp for a retry to
     * consume; a crash after commit but before the clear leaves a stale stamp
     * behind. None of those tears the just-written session or set rows. A stale
     * stamp is contained on two fronts: the date-scoped read ([sessionStartedAtFlow])
     * drops it outright once the calendar day turns over, and even within the
     * same day both `:transfer` exports (HC calories, CSV Duration) refuse a
     * span outside a sane 5 min–6 h window — so the worst case is a session
     * whose `startedAt` is simply absent, never a garbage duration.
     */
    suspend fun advanceDay(completedDayId: String): Long {
        val bodyweight = settings.configFlow.first().bodyweightLb
        // Date-scoped read (see [sessionStartedAtFlow]): a stamp from a day the
        // user ticked but never advanced reads as null here, so the completed
        // session records no start rather than inheriting the abandoned one.
        val sessionStartedAt = sessionStartedAtFlow.first()
        val catalog = ExerciseCatalog(customExerciseDao.getAll().map { it.toEntry() })
        var next: String? = null
        var newSessionId = 0L
        db.withTransaction {
            val days = programDao.allDays()
            val exercises = programDao.allExercises()
            val dayTitle = days.firstOrNull { it.dayId == completedDayId }?.title ?: completedDayId
            val slotsById = exercises.filter { it.dayId == completedDayId }.associateBy { it.id }
            val logs = programDao.logsForDay(completedDayId)

            val sessionId = sessionDao.insertSession(
                WorkoutSessionEntity(
                    id = 0,
                    dayId = completedDayId,
                    dayTitle = dayTitle,
                    startedAt = sessionStartedAt,
                    completedAt = clock.millis(),
                    bodyweightLb = bodyweight,
                ),
            )
            newSessionId = sessionId
            val sessionSets = logs.flatMap { log ->
                val pe = slotsById[log.programExerciseId] ?: return@flatMap emptyList()
                val exerciseId = if (log.slot == Slot.SS) pe.supersetExerciseId else pe.exerciseId
                if (exerciseId == null) return@flatMap emptyList()
                val name = catalog.find(exerciseId)?.name ?: exerciseId
                SetJson.decodeSets(log.setsJson).mapIndexed { index, s ->
                    SessionSetEntity(
                        id = 0,
                        sessionId = sessionId,
                        exerciseId = exerciseId,
                        exerciseName = name,
                        slot = log.slot,
                        setIndex = index,
                        kind = s.kind.name,
                        weightLb = s.weightLb,
                        reps = s.reps,
                        done = s.done,
                        seconds = s.seconds,
                    )
                }
            }
            if (sessionSets.isNotEmpty()) sessionDao.insertSets(sessionSets)

            programDao.clearChecksForDay(completedDayId)

            val program = assemble(days, exercises)
            if (program.days.any { it.id == completedDayId }) {
                next = Rotation.next(program, completedDayId)
            }
        }
        // Consumed: cleared only once the session row above is durably committed
        // (see the crash-ordering note on this method).
        settings.clearSessionStartedAt()
        next?.let { settings.setSuggestedDay(it) }
        return newSessionId
    }

    // --- full backup (A2) ----------------------------------------------------

    /**
     * Reads everything the user owns into one [FullSnapshot] for the A2 backup.
     * Every list comes from a query with an explicit `ORDER BY`, so the output is
     * deterministic — two exports of the same state are byte-identical, keeping
     * diffs and round-trip tests honest.
     */
    suspend fun exportSnapshot(): FullSnapshot = FullSnapshot(
        answers = settings.wizardAnswersFlow.first(),
        unit = settings.unitFlow.first(),
        wizardComplete = settings.wizardCompleteFlow.first(),
        suggestedDay = settings.suggestedDayFlow.first(),
        customExercises = customExerciseDao.allOrdered(),
        days = programDao.allDays(),
        exercises = programDao.allExercises(),
        logs = programDao.allLogs(),
        sessions = sessionDao.allSessions(),
        sessionSets = sessionDao.allSessionSets(),
    )

    /**
     * Replaces the device's entire state with [snapshot] (A2 restore). The caller
     * (`:transfer`) has already validated the backup end-to-end; this method does
     * no validation and performs an unconditional destructive replace.
     *
     * Atomicity across two independent stores. Room and DataStore each commit
     * atomically on their own, but there is no transaction spanning both, so a
     * crash can land between them. The write order makes that window safe: the
     * only cross-store reference is `suggestedDay` (DataStore) pointing at a
     * `dayId` (Room), so the referenced program is written *first*. A crash after
     * the Room transaction but before the DataStore edit therefore leaves fully
     * consistent new training data (program, logs, history and customs all swapped
     * as one transaction) with at worst a stale rotation pointer — which is a
     * nullable value the app already resolves against the live program, never a
     * torn or crashing state. The reverse order could publish a pointer into a
     * program that does not exist yet, so it is deliberately avoided.
     */
    suspend fun importSnapshot(snapshot: FullSnapshot) {
        db.withTransaction {
            programDao.deleteAllLogs()
            programDao.deleteAllExercises()
            programDao.deleteAllDays()
            sessionDao.deleteAllSessionSets()
            sessionDao.deleteAllSessions()
            customExerciseDao.deleteAll()

            customExerciseDao.upsertAll(snapshot.customExercises)
            programDao.upsertDays(snapshot.days)
            programDao.insertExercises(snapshot.exercises)
            programDao.insertLogs(snapshot.logs)
            sessionDao.insertSessions(snapshot.sessions)
            sessionDao.insertSets(snapshot.sessionSets)
        }
        settings.restore(snapshot.answers, snapshot.unit, snapshot.wizardComplete, snapshot.suggestedDay)
    }

    // --- CSV history export/import (#16) --------------------------------------

    /**
     * Every session + set for CSV history export (#16), in the same
     * deterministic order as [exportSnapshot]'s equivalent fields. Read-only —
     * `:transfer` builds the CSV text from this instead of touching Room.
     */
    suspend fun exportSessionHistory(): SessionHistorySnapshot = SessionHistorySnapshot(
        unit = settings.unitFlow.first(),
        sessions = sessionDao.allSessions(),
        sessionSets = sessionDao.allSessionSets(),
    )

    /**
     * Appends CSV-imported history in one transaction (#16, D9's one staging
     * transaction rule). Additive only: unlike [importSnapshot]'s full
     * destructive replace, the program and live logs are untouched.
     * [newCustomExercises] are upserted first so every imported set's
     * `exerciseId` already resolves by the time its row lands; each
     * [ImportedSession] then gets a freshly generated session id stamped onto
     * its own sets before they're inserted, exactly as [advanceDay] links a
     * completed day's sets to the session it just created.
     *
     * The caller has already validated the file and the user has already
     * confirmed the exercise-name matches (`:transfer`'s preview/confirm
     * model) — this method performs no validation of its own and always
     * commits what it's given.
     */
    suspend fun importSessionHistory(
        sessions: List<ImportedSession>,
        newCustomExercises: List<CustomExerciseEntity>,
    ) {
        db.withTransaction {
            if (newCustomExercises.isNotEmpty()) customExerciseDao.upsertAll(newCustomExercises)
            sessions.forEach { imported ->
                val sessionId = sessionDao.insertSession(imported.session.copy(id = 0))
                if (imported.sets.isNotEmpty()) {
                    sessionDao.insertSets(imported.sets.map { it.copy(id = 0, sessionId = sessionId) })
                }
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun assemble(
        days: List<ProgramDayEntity>,
        exercises: List<ProgramExerciseEntity>,
    ): Program {
        val byDay = exercises.groupBy { it.dayId }
        return Program(
            days.sortedBy { it.position }.map { day -> day.toDomain(byDay[day.dayId].orEmpty()) },
        )
    }

    private fun ExerciseLogEntity.toLoggedSlot(today: String): LoggedSlot {
        val stored = SetJson.decodeSets(setsJson)
        return LoggedSlot(
            programExerciseId = programExerciseId,
            slot = slot,
            sets = CheckmarkReset.applyResetIfStale(stored, checkDate, today),
            checkDate = checkDate,
        )
    }
}
