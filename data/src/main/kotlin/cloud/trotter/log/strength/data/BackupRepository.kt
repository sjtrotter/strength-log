package cloud.trotter.log.strength.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.CustomExerciseDao
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.run.LegacyLiveLogSplit
import cloud.trotter.log.strength.data.run.SplitInput
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class BackupRepository(
    private val db: StrengthDatabase,
    private val settings: SettingsStore,
    private val plans: PlanRepository,
    private val history: HistoryRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val mintRunId: () -> String = { UUID.randomUUID().toString() },
    private val programDao: ProgramDao = db.programDao(),
    private val sessionDao: SessionDao = db.sessionDao(),
    private val customExerciseDao: CustomExerciseDao = db.customExerciseDao(),
) {
    /** Taken off [db] rather than injected like the other DAOs on purpose: the
     *  restore marker is this class's own crash bookkeeping — never data a
     *  caller supplies, reads, or would substitute — and threading it through
     *  every construction site would claim otherwise. */
    private val restoreMarkerDao = db.restoreMarkerDao()
    private val cardioSessionDao = db.cardioSessionDao()
    private val cardioSetResultDao = db.cardioSetResultDao()
    private val planDao = db.planDao()
    private val runDao = db.runDao()

    // --- full backup (A2) ----------------------------------------------------

    /**
     * Reads everything the user owns into one [FullSnapshot] for the A2 backup.
     * Every list comes from a query with an explicit `ORDER BY`, so the output is
     * deterministic — two exports of the same state are byte-identical, making
     * diffs and round-trip tests reliable.
     */
    suspend fun exportSnapshot(): FullSnapshot {
        plans.ensurePlanSettingsMigrated()
        history.ensureSessionNotesMoved()
        // DataStore and Room have independent commits. Read preferences first;
        // never collect a DataStore-backed flow while holding a Room transaction.
        val global = settings.globalLifterFlow.first()
        val unit = settings.unitFlow.first()
        val wizardComplete = settings.wizardCompleteFlow.first()
        val selectedPlanId = settings.selectedPlanIdFlow.first()
        val restSettings = settings.restSettingsFlow.first()
        val keepScreenOn = settings.keepScreenOnFlow.first()
        val topSetHelperSeen = settings.topSetHelperSeenFlow.first()
        val supersetHelperSeen = settings.supersetHelperSeenFlow.first()
        val themePreference = settings.themePreferenceFlow.first()
        return db.withTransaction {
            // Use this same plan list for the legacy settings projection, too.
            // Separate reads could otherwise straddle a restore's Room commit.
            val plans = planDao.allPlans()
            val selectedPlan = preferredPlan(plans, selectedPlanId)
            FullSnapshot(
                answers = answersForPlan(selectedPlan, global),
                answersCarryRecipe = false,
                unit = unit,
                wizardComplete = wizardComplete,
                suggestedDay = selectedPlan?.suggestedDayId,
                selectedPlanId = selectedPlanId,
                restSettings = restSettings,
                keepScreenOn = keepScreenOn,
                topSetHelperSeen = topSetHelperSeen,
                supersetHelperSeen = supersetHelperSeen,
                themePreference = themePreference,
                plans = plans,
                customExercises = customExerciseDao.allOrdered(),
                days = programDao.allDays(),
                exercises = programDao.allExercises(),
                logs = programDao.allLogs(),
                sessions = sessionDao.allSessions(),
                sessionSets = sessionDao.allSessionSets(),
                cardioSessions = cardioSessionDao.all(),
                cardioSetResults = cardioSetResultDao.all(),
                runs = runDao.allRuns(),
                runTracks = runDao.allTracks(),
            )
        }
    }

    /**
     * Replaces the device's entire state with [snapshot] (A2 restore). The caller
     * (`:transfer`) has already validated the backup end-to-end; this method does
     * no validation and performs an unconditional destructive replace.
     *
     * Two independent stores, no shared transaction. Room and DataStore each
     * commit atomically on their own; nothing spans both. So rather than claim an
     * atomicity it can't have, this method makes the pair *recoverable*:
     *
     *  1. [journal] stages the settings half under a fresh nonce, before
     *     anything is destroyed.
     *  2. The destructive Room transaction writes that same nonce into
     *     `restore_marker` — *inside* the transaction, so the marker exists if
     *     and only if the data half committed. That is the whole point: a flag
     *     written after the transaction can be lost in the gap between two
     *     durable commits, and losing it would discard the only copy of the
     *     settings half.
     *  3. The settings write lands, then the journal and the marker both clear.
     *
     * Interrupted anywhere, the next launch reads the marker, matches it against
     * the staged nonce, and either finishes the settings half or discards a
     * payload whose transaction never committed ([reconcilePendingRestore]). The
     * interim state is not benign and is not treated as such — restored training
     * data paired with the device's pre-restore config means every derived GOAL
     * is wrong until the replay happens.
     *
     * Room goes before the settings write because the only cross-store pointer is
     * `selectedPlanId` pointing at a plan, and the reverse order could publish a
     * pointer into a program that does not exist yet. The tail is [NonCancellable]
     * so a cancelled caller still *finishes* here instead of deferring to the next
     * launch — belt to the marker's braces, not the correctness argument. The
     * transaction itself stays cancellable: rolling it back leaves the device
     * untouched, which is a clean outcome.
     *
     * Failures are reported by phase ([RestoreInterruption]) rather than as the
     * raw [IOException] the UI would report as a problem reading the file.
     */
    suspend fun importSnapshot(snapshot: FullSnapshot, journal: RestoreJournal) {
        try {
            history.ensureSessionNotesMoved()
        } catch (e: IOException) {
            throw RestoreInterruption.NotStarted(e)
        }
        val days = snapshot.days.map { if (it.nextRunId.isBlank()) it.freshRunId(mintRunId) else it }
        val split = snapshot.legacyLogs.takeIf { it.isNotEmpty() }?.let { legacy ->
            val customNames = snapshot.customExercises.associate { it.id to it.name }
            LegacyLiveLogSplit.split(SplitInput(
                logs = legacy, slots = snapshot.exercises, days = days,
                planNames = snapshot.plans.associate { it.id to it.name }.ifEmpty {
                    mapOf(PlanEntity.LEGACY_ID to PlanEntity.LEGACY_NAME)
                },
                exerciseName = { ExerciseLibrary.find(it)?.name ?: customNames[it] ?: it },
                today = LocalDate.now(clock).toString(), now = clock.millis(),
                openRun = snapshot.runs.firstOrNull()?.let { it.planId to it.dayId }, mint = mintRunId,
            ))
        }
        val nonce = UUID.randomUUID().toString()
        val legacyPlanCreated = snapshot.plans.isEmpty() && snapshot.answersCarryRecipe
        val restoredSelectedPlanId = snapshot.selectedPlanId
            ?: if (legacyPlanCreated) PlanEntity.LEGACY_ID else null
        val stagedSnapshot = snapshot.copy(selectedPlanId = restoredSelectedPlanId)
        try {
            journal.stage(stagedSnapshot, nonce)
        } catch (e: IOException) {
            throw RestoreInterruption.NotStarted(e)
        }
        db.withTransaction {
            runDao.deleteAllTracks()
            runDao.deleteAllRuns()
            programDao.deleteAllLogs()
            programDao.deleteAllExercises()
            programDao.deleteAllDays()
            planDao.deleteAllPlans()
            sessionDao.deleteAllSessionSets()
            sessionDao.deleteAllSessions()
            cardioSetResultDao.deleteAll()
            cardioSessionDao.deleteAll()
            customExerciseDao.deleteAll()

            if (snapshot.plans.isNotEmpty()) {
                planDao.insertPlans(snapshot.plans)
            } else if (legacyPlanCreated) {
                planDao.insertPlan(
                    PlanEntity(
                        id = PlanEntity.LEGACY_ID,
                        name = PlanEntity.LEGACY_NAME,
                        onToday = true,
                        origin = PlanOrigin.GENERATED.name,
                        position = 0,
                        createdAt = clock.millis(),
                    ),
                )
            }
            if (snapshot.answersCarryRecipe && snapshot.plans.none { it.recipeJson != null }) {
                val recipe = PlanRecipeJson.encode(snapshot.answers)
                val plans = planDao.allPlans()
                plans.filter { it.origin == PlanOrigin.GENERATED.name && it.recipeJson == null }
                    .forEach { planDao.updateRecipe(it.id, recipe) }
                val selectedId = preferredPlan(plans, restoredSelectedPlanId)?.id
                if (selectedId != null && planDao.plan(selectedId)?.suggestedDayId == null) {
                    planDao.updateSuggestedDay(selectedId, snapshot.suggestedDay)
                }
            }
            customExerciseDao.upsertAll(snapshot.customExercises)
            programDao.upsertDays(days)
            programDao.insertExercises(snapshot.exercises)
            programDao.insertLogs(snapshot.logs + split?.logs.orEmpty())
            sessionDao.insertSessions(snapshot.sessions)
            sessionDao.insertSets(snapshot.sessionSets)
            cardioSessionDao.insertAll(snapshot.cardioSessions)
            cardioSetResultDao.insertAll(snapshot.cardioSetResults)
            runDao.insertRuns(snapshot.runs)
            runDao.insertTracks(snapshot.runTracks)
            split?.run?.let { runDao.insertRun(it) }
            split?.tracks?.let { runDao.insertTracks(it) }
            split?.archived?.forEach { archived ->
                val id = sessionDao.insertSession(archived.session)
                sessionDao.insertSets(archived.sets.map { it.copy(sessionId = id) })
            }
            split?.closes?.forEach { close ->
                val day = programDao.day(close.planId, close.dayId) ?: return@forEach
                programDao.upsertDay(day.copy(nextRunId = close.nextRunId, closedAt = close.closedAt))
            }
            restoreMarkerDao.put(RestoreMarkerEntity(nonce = nonce))
        }
        withContext(NonCancellable) {
            try {
                settings.restore(
                    global = GlobalLifter.of(snapshot.answers.config),
                    unit = snapshot.unit,
                    wizardComplete = snapshot.wizardComplete,
                    selectedPlanId = restoredSelectedPlanId,
                    restSettings = snapshot.restSettings,
                    keepScreenOn = snapshot.keepScreenOn,
                    topSetHelperSeen = snapshot.topSetHelperSeen,
                    supersetHelperSeen = snapshot.supersetHelperSeen,
                    themePreference = snapshot.themePreference,
                    onTodayFixupDone = true,
                )
            } catch (e: IOException) {
                throw RestoreInterruption.SettingsPending(e)
            }
            plans.invalidateLegacyCardioConversion()
            clearRestoreBookkeeping(journal)
            try {
                plans.ensurePlanSettingsMigrated()
            } catch (e: IOException) {
                throw RestoreInterruption.CleanupPending(e)
            } catch (e: SQLiteException) {
                throw RestoreInterruption.CleanupPending(e)
            }
        }
    }

    /**
     * Finishes an interrupted restore at startup, returning whether it replayed
     * one. Old-format journals overwrite every generated plan's recipe and the
     * selected plan's rotation when staged: the restore replaced Room, so any
     * existing settings came from interim edits. The caller must prevent overlap
     * with a live restore (`:transfer` owns that lock).
     */
    suspend fun reconcilePendingRestore(journal: RestoreJournal): Boolean {
        val committed = restoreMarkerDao.nonce()
        val replayed = journal.reconcile(committed, applyLegacySessionNotes = { notes ->
            db.withTransaction {
                notes.forEach { (id, note) -> sessionDao.setNote(id, note.trim().take(120)) }
            }
        }) { answers, suggestedDay, storedId ->
            db.withTransaction {
                val recipe = PlanRecipeJson.encode(answers)
                val plans = planDao.allPlans()
                if (plans.isEmpty()) {
                    planDao.insertPlan(
                        PlanEntity(
                            id = PlanEntity.LEGACY_ID,
                            name = PlanEntity.LEGACY_NAME,
                            onToday = true,
                            origin = PlanOrigin.GENERATED.name,
                            position = 0,
                            createdAt = clock.millis(),
                            recipeJson = recipe,
                            suggestedDayId = suggestedDay,
                        ),
                    )
                    return@withTransaction PlanEntity.LEGACY_ID
                }
                plans.filter { it.origin == PlanOrigin.GENERATED.name }
                    .forEach { planDao.updateRecipe(it.id, recipe) }
                val selected = preferredPlan(plans, storedId)
                if (selected != null && suggestedDay != null) {
                    planDao.updateSuggestedDay(selected.id, suggestedDay)
                }
                selected?.id
            }
        }
        plans.invalidateLegacyCardioConversion()
        if (committed != null) restoreMarkerDao.clear()
        plans.ensurePlanSettingsMigrated(migrateOnToday = false)
        return replayed
    }

    /** Drops both halves of the restore bookkeeping. A failure here means the
     *  restore itself fully landed and only the paperwork is outstanding — the
     *  leftover pair replays the same values idempotently next launch — so it is
     *  reported as that, not as a failed restore. */
    private suspend fun clearRestoreBookkeeping(journal: RestoreJournal) {
        try {
            journal.clear()
            restoreMarkerDao.clear()
        } catch (e: IOException) {
            throw RestoreInterruption.CleanupPending(e)
        } catch (e: RuntimeException) {
            // Room surfaces storage failures as SQLiteException, not IOException;
            // a cleanup failure is the same success-with-a-footnote either way.
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw RestoreInterruption.CleanupPending(e)
        }
    }

}
