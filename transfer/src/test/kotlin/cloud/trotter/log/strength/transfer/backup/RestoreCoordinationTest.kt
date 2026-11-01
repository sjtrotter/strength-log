package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.CardioDto
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.TodayTarget
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.transfer.FlakyDataStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The turn-taking [BackupService] owes the restore journal. A restore and the
 * startup reconciliation drive the same payload, so they must never overlap: a
 * reconcile reading mid-restore would replay a journal the restore is still
 * writing, and two restores at once would interleave two nonces through one
 * marker row.
 *
 * Real repository, real service, real Room — only the settings DataStore is
 * instrumented, so a restore can be held open at a known point while something
 * else tries to run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreCoordinationTest {

    private lateinit var db: StrengthDatabase
    private lateinit var storeScope: CoroutineScope
    private lateinit var settingsStore: FlakyDataStore
    private lateinit var settings: SettingsStore
    private lateinit var journal: RestoreJournal
    private lateinit var repo: TrackerRepository
    private lateinit var service: BackupService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settingsStore = FlakyDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("restore-coordination-settings", ".preferences_pb")
            },
        )
        settings = SettingsStore(settingsStore)
        journal = RestoreJournal(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("restore-coordination-journal", ".preferences_pb")
            },
            settings,
        )
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
        service = BackupService(repo, journal)
    }

    @Test
    fun liveTodayTargetResumesAfterImportClearsItsMarkerWithoutAnotherWrite() = runBlocking {
        service.import(backupJson("A", 190))
        val observed = Channel<TodayTarget?>(Channel.UNLIMITED)
        val collector = storeScope.launch { repo.todayTargetFlow.collect { observed.send(it) } }
        suspend fun awaitTarget(predicate: (TodayTarget?) -> Boolean): TodayTarget? = withTimeout(TIMEOUT_MS) {
            var target = observed.receive()
            while (!predicate(target)) target = observed.receive()
            target
        }
        val open = CompletableDeferred<Unit>()
        try {
            awaitTarget { it is TodayTarget.Day && it.dayId == "A" }
            val snapshot = BackupCodec().decode(backupJson("B", 205)).toSnapshot()
            val gatedSettings = FlakyDataStore(settingsStore)
            gatedSettings.gateOnUpdate = 1
            gatedSettings.gate = open
            val importingRepo = TrackerRepository(
                db, db.programDao(), db.sessionDao(), db.customExerciseDao(), SettingsStore(gatedSettings),
            )
            val importing = storeScope.async { importingRepo.importSnapshot(snapshot, journal) }
            withTimeout(TIMEOUT_MS) { gatedSettings.gateReached.await() }
            awaitTarget { it == null }
            open.complete(Unit)
            withTimeout(TIMEOUT_MS) { importing.await() }
            val target = awaitTarget { it is TodayTarget.Day && it.dayId == "B" } as TodayTarget.Day
            assertEquals(PlanEntity.LEGACY_ID, target.plan.id)
            assertNull(db.restoreMarkerDao().nonce())
        } finally {
            open.complete(Unit)
            collector.cancelAndJoin()
        }
    }

    @Test
    fun v15ImportAndInterruptedReplayPreserveAllPaused() = runBlocking {
        val document = BackupDocument(
            settings = SettingsBackup(190, 33, "NOVICE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
            plans = listOf(PlanBackup(1, "Paused", "GENERATED", 1000, onToday = false)),
        )
        val text = BackupCodec().encode(document)
        service.import(text)
        assertTrue(settings.onTodayFixupDoneFlow.first())
        assertFalse(db.planDao().plan(1)!!.onToday)
        val nonce = "membership-replay"
        journal.stage(repo.exportSnapshot(), nonce)
        db.restoreMarkerDao().put(cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity(nonce = nonce))
        service.withRestoreLock {
            assertTrue(settings.onTodayFixupDoneFlow.first())
            assertFalse(db.planDao().plan(1)!!.onToday)
            assertNull(db.restoreMarkerDao().nonce())
        }
    }

    /** [F] */
    @Test fun `legacy v12 ticks split through importSnapshot by the restore clock date`() = runBlocking {
        val codec = BackupCodec()
        val setJson = """[{"weightLb":235.0,"reps":5,"kind":"TOP","done":true}]"""
        val doc = BackupDocument(
            schemaVersion = 12,
            settings = SettingsBackup(200, 30, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true),
            plans = listOf(PlanBackup(1, "My plan", "GENERATED", 0, false, days = listOf(
                ProgramDayBackup("A", "Day A", "", null, exercises = listOf(
                    ProgramExerciseBackup(1, "bb_back_squat", true, 1, "5", false, null, ""),
                )),
            ))),
            liveLogs = listOf(LiveLogBackup(1, "A", 1, "main", setJson, "2026-10-19", 6_000)),
        )
        val snapshot = codec.decode(codec.encode(doc)).toSnapshot()
        assertTrue(snapshot.logs.isEmpty())
        assertEquals(1, snapshot.legacyLogs.size)
        for (date in listOf("2026-10-19", "2026-10-20")) {
            val clock = java.time.Clock.fixed(java.time.Instant.parse("${date}T12:00:00Z"), java.time.ZoneOffset.UTC)
            val repository = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings,
                clock = clock, mintRunId = { "restored-A" })
            repository.importSnapshot(snapshot, journal)
            val open = repository.openRunFlow.first()
            if (date == "2026-10-19") {
                assertEquals("restored-A", open!!.id)
                assertTrue(open.tracks.single().sets.single().done)
                assertEquals(open.id, db.programDao().day(1, "A")!!.nextRunId)
            } else {
                assertNull(open)
                assertTrue(db.sessionDao().allSessions().isEmpty())
                assertTrue(cloud.trotter.log.strength.data.serialization.SetJson.decodeSets(
                    db.programDao().allLogs().single().setsJson).none { it.done })
            }
        }
    }

    @Test
    fun v8AndV13ProseCardioUseRestoredRecipesBeforeImportReturnsAndExportNoProse() = runBlocking {
        val codec = BackupCodec()
        val suggestion = CardioSuggestion("Intervals", "Run hard, recover easy.", true)
        val prose = CardioDto.encode(suggestion)
        val answers = WizardAnswers(
            cardio = CardioPrefs(
                mode = CardioMode.TREADMILL, fiveKGoal = false,
            ),
        )
        val expected = CardioBlocks.fromLegacy(
            suggestion, false, CardioActivity.TREADMILL_RUN,
        )
        val day = ProgramDayBackup("C1", "Cardio + Core", "Easy", prose, kind = "CARDIO", exercises = listOf(
            ProgramExerciseBackup(1, "plank", false, 3, "30s", false, note = ""),
        ))
        val legacy = codec.decode(backupJson("A", 190))
        for (version in listOf(8, 13)) {
            val document = BackupDocument(
                schemaVersion = version,
                settings = if (version == 8) legacy.settings.copy(
                    cardioMode = "TREADMILL", cardioPlacement = "SEPARATE_DAYS", fiveKGoal = false,
                    selectedPlanId = 7, suggestedDay = "C1",
                ) else SettingsBackup(190, 33, "NOVICE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 7),
                plans = listOf(PlanBackup(7, "Running", "GENERATED", 1_000, days = listOf(day),
                    recipeJson = if (version == 13) PlanRecipeJson.encode(answers) else null,
                    suggestedDay = if (version == 13) "C1" else null)),
            )
            val snapshot = codec.decode(codec.encode(document)).toSnapshot()
            assertEquals(version == 8, snapshot.answersCarryRecipe)

            repo.importSnapshot(snapshot, journal)

            val stored = db.programDao().day(7, "C1")!!
            assertNull(stored.cardioJson)
            assertEquals(expected, CardioBlockJson.decode(stored.cardioBlockJson))
            assertEquals(expected, repo.programFor(7).first().cardioDays.single().cardioBlock)
            assertEquals(snapshot.plans.single().recipeJson
                ?: PlanRecipeJson.encode(snapshot.answers),
                db.planDao().plan(7)!!.recipeJson)
            assertNull(db.restoreMarkerDao().nonce())
            val exported = codec.decode(codec.encode(repo.exportSnapshot().toDocument()))
            assertTrue(exported.plans.flatMap { it.days }.all { it.cardioJson == null })
            assertEquals(stored.cardioBlockJson, exported.plans.single().days.single().cardioBlockJson)
        }
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    /** A minimal but valid backup naming one day and one catalog exercise. */
    private fun backupJson(dayId: String, bodyweightLb: Int): String = BackupCodec().encode(
        BackupDocument(
            schemaVersion = 7,
            settings = SettingsBackup(
                bodyweightLb = bodyweightLb, age = 33, level = "NOVICE", emphasis = "STRENGTH",
                cardioMode = "NONE", cardioPlacement = "NONE", fiveKGoal = false,
                daysPerWeek = 3, split = "FULL_BODY", anchorScheme = "BIG_4",
                deadliftVariant = "SUMO", equipment = listOf("BARBELL"), weightUnit = "LB",
                wizardComplete = true, suggestedDay = dayId,
            ),
            program = listOf(
                ProgramDayBackup(
                    dayId = dayId,
                    title = "Day $dayId",
                    emphasisLine = "Squat-focused",
                    exercises = listOf(ProgramExerciseBackup(1, "bb_back_squat", true, 4, "5/5/5/3", true, null, "")),
                ),
            ),
        ),
    )

    @Test
    fun withRestoreLockFinishesPendingRecoveryBeforeRunningTheBlock() = runBlocking {
        settingsStore.failOnUpdate = 1
        val failure = runCatching { service.import(backupJson("A", 190)) }.exceptionOrNull()
        assertTrue(failure is RestoreInterruption.SettingsPending)
        assertNotNull(db.restoreMarkerDao().nonce())
        assertFalse(settings.wizardCompleteFlow.first())

        val result = service.withRestoreLock {
            assertEquals(190, settings.globalLifterFlow.first().bodyweightLb)
            assertTrue(settings.wizardCompleteFlow.first())
            assertEquals(listOf("A"), repo.programFlow.first().days.map { it.id })
            assertNull(db.restoreMarkerDao().nonce())
            "settled"
        }

        assertEquals("settled", result)
        assertFalse(service.reconcilePendingRestore())
    }

    @Test
    fun withRestoreLockReplaysInterruptedV9RestoreWithNoPlans() = runBlocking {
        val document = BackupDocument(
            schemaVersion = 9,
            settings = SettingsBackup(
                bodyweightLb = 190, age = 33, level = "NOVICE", weightUnit = "LB", wizardComplete = true,
            ),
            plans = emptyList(),
        )
        settingsStore.failOnUpdate = 1
        val failure = runCatching { service.import(BackupCodec().encode(document)) }.exceptionOrNull()
        assertTrue(failure is RestoreInterruption.SettingsPending)
        assertNotNull(db.restoreMarkerDao().nonce())
        assertFalse(settings.wizardCompleteFlow.first())

        val result = service.withRestoreLock {
            assertEquals(190, settings.globalLifterFlow.first().bodyweightLb)
            assertTrue(settings.wizardCompleteFlow.first())
            assertTrue(db.planDao().allPlans().isEmpty())
            assertTrue(repo.programFlow.first().days.isEmpty())
            assertNull(settings.selectedPlanIdFlow.first())
            assertNull(db.restoreMarkerDao().nonce())
            "settled"
        }

        assertEquals("settled", result)
        assertFalse(service.reconcilePendingRestore())
    }

    @Test
    fun interruptedRestorePreservesRoomNotesForBothSessionsUnderTheRestoreLock() = runBlocking {
        val notes = mapOf(11L to "Easy first session", 12L to "Add weight next time")
        val codec = BackupCodec()
        val document = BackupDocument(
            settings = SettingsBackup(190, 33, "NOVICE", weightUnit = "LB", wizardComplete = true, sessionNotes = notes),
            sessions = listOf(
                SessionBackup(11, "A", "Day A", null, 1_000, 190, emptyList()),
                SessionBackup(12, "A", "Day A", null, 2_000, 190, emptyList()),
            ),
        )
        settingsStore.failOnUpdate = 1

        val failure = runCatching { service.import(codec.encode(document)) }.exceptionOrNull()

        assertTrue(failure is RestoreInterruption.SettingsPending)
        assertEquals(listOf(11L, 12L), db.sessionDao().allSessions().map { it.id })
        assertNotNull(db.restoreMarkerDao().nonce())
        assertTrue(settings.legacySessionNotesFlow.first().isEmpty())

        service.withRestoreLock {
            assertEquals(notes, repo.sessionNotesFlow.first())
            assertTrue(settings.legacySessionNotesFlow.first().isEmpty())
            assertNull(db.restoreMarkerDao().nonce())
        }
        assertFalse(service.reconcilePendingRestore())
        assertEquals(notes, repo.sessionNotesFlow.first())
        assertTrue(settings.legacySessionNotesFlow.first().isEmpty())
    }

    @Test
    fun reconciliationWaitsForAnInFlightRestoreInsteadOfReadingItsJournal() = runBlocking {
        val open = CompletableDeferred<Unit>()
        settingsStore.gateOnUpdate = 1
        settingsStore.gate = open

        val restore = storeScope.async { service.import(backupJson("A", 190)) }
        withTimeout(TIMEOUT_MS) { settingsStore.gateReached.await() }

        val reconcile = storeScope.async { service.reconcilePendingRestore() }
        // Long enough to have finished if nothing were holding it: the journal it
        // would read is mid-write, so it must still be at the door.
        delay(200)
        assertFalse("reconcile must not run beside a restore", reconcile.isCompleted)

        open.complete(Unit)
        withTimeout(TIMEOUT_MS) { restore.await() }
        val replayed = withTimeout(TIMEOUT_MS) { reconcile.await() }

        assertFalse("by its turn the restore had finished, so there was nothing left", replayed)
        assertEquals(listOf("A"), repo.programFlow.first().days.map { it.id })
        assertEquals(190, settings.globalLifterFlow.first().bodyweightLb)
        assertNull(db.restoreMarkerDao().nonce())
    }

    @Test
    fun twoRestoresBackToBackLeaveTheSecondOneWholeAndNothingOutstanding() = runBlocking {
        service.import(backupJson("A", 190))
        service.import(backupJson("B", 205))

        assertEquals(listOf("B"), repo.programFlow.first().days.map { it.id })
        assertEquals(205, settings.globalLifterFlow.first().bodyweightLb)
        assertEquals("B", repo.suggestedDayFlow.first())
        assertNull("the second restore cleared its own marker", db.restoreMarkerDao().nonce())
        assertFalse(service.reconcilePendingRestore())
    }

    @Test
    fun twoRestoresRacingTakeTurnsRatherThanInterleave() = runBlocking {
        val text = backupJson("A", 190)

        // Same document twice so the outcome is deterministic; what's under test
        // is that neither run sees the other's journal or marker half-written.
        withTimeout(TIMEOUT_MS) {
            listOf(
                storeScope.async { service.import(text) },
                storeScope.async { service.import(text) },
            ).awaitAll()
        }

        assertEquals(listOf("A"), repo.programFlow.first().days.map { it.id })
        assertEquals(190, settings.globalLifterFlow.first().bodyweightLb)
        assertTrue(settings.wizardCompleteFlow.first())
        assertNull(db.restoreMarkerDao().nonce())
        assertFalse(service.reconcilePendingRestore())
    }

    @Test
    fun v9WithNoPlansRestoresOnlyTheGlobalLifterWithoutCreatingALegacyPlan() = runBlocking {
        service.import(backupJson("A", 190))
        assertEquals(1, db.planDao().allPlans().size)
        val document = BackupDocument(
            settings = SettingsBackup(
                bodyweightLb = 210, age = 33, level = "ADVANCED", weightUnit = "KG", wizardComplete = true,
            ),
        )

        service.import(BackupCodec().encode(document))

        assertTrue(db.planDao().allPlans().isEmpty())
        assertTrue(repo.programFlow.first().days.isEmpty())
        assertNull(settings.selectedPlanIdFlow.first())
        assertNull(repo.suggestedDayFlow.first())
        val global = settings.globalLifterFlow.first()
        assertEquals(210, global.bodyweightLb)
        assertEquals(33, global.age)
        assertEquals("ADVANCED", global.level.name)
        assertTrue(settings.wizardCompleteFlow.first())
        assertFalse(repo.exportSnapshot().answersCarryRecipe)
    }

    @Test
    fun v9GlobalAnswersDoNotSupplyARecipeToAGeneratedPlan() = runBlocking {
        val document = BackupDocument(
            settings = SettingsBackup(
                bodyweightLb = 210, age = 33, level = "ADVANCED", weightUnit = "KG", wizardComplete = true,
                selectedPlanId = 7,
            ),
            plans = listOf(PlanBackup(7, "Recipe-less", "GENERATED", 1_000)),
        )

        service.import(BackupCodec().encode(document))

        assertEquals(listOf(PlanEntity(7, "Recipe-less", "GENERATED", 0, 1_000)), db.planDao().allPlans())
        assertFalse(repo.exportSnapshot().answersCarryRecipe)
    }

    @Test
    fun v8StillSpreadsItsSettingsRecipeAcrossGeneratedPlansAndRotatesTheSelectedPlan() = runBlocking {
        val codec = BackupCodec()
        val legacy = codec.decode(backupJson("A", 190))
        val document = legacy.copy(
            schemaVersion = 8,
            settings = legacy.settings.copy(selectedPlanId = 7),
            program = emptyList(),
            plans = listOf(
                PlanBackup(7, "Selected", "GENERATED", 1_000, days = legacy.program),
                PlanBackup(8, "Other", "GENERATED", 2_000),
                PlanBackup(9, "Authored", "AUTHORED", 3_000),
            ),
        )

        service.import(codec.encode(document))

        val plans = db.planDao().allPlans()
        assertNotNull(plans[0].recipeJson)
        assertEquals(plans[0].recipeJson, plans[1].recipeJson)
        assertNull(plans[2].recipeJson)
        assertEquals(listOf("A", null, null), plans.map { it.suggestedDayId })
        assertEquals(legacy.toSnapshot().answers, repo.wizardAnswersFlow.first())
        assertEquals(7L, settings.selectedPlanIdFlow.first())
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }

    @Test
    fun v14ImportMovesLegacyNotesBeforeReplacingSessionsWithMatchingIds() = runBlocking {
        db.sessionDao().insertSessions(listOf(WorkoutSessionEntity(11, "A", "Local", null, 1_000, 190)))
        val key = stringPreferencesKey("session_notes")
        settingsStore.edit { it[key] = """{"11":"Local legacy note"}""" }
        val document = BackupDocument(
            settings = SettingsBackup(190, 33, "NOVICE", weightUnit = "LB", wizardComplete = true),
            sessions = listOf(SessionBackup(11, "A", "Restored", completedAt = 2_000, note = "Document note")),
        )
        val open = CompletableDeferred<Unit>()
        settingsStore.gateOnUpdate = 2
        settingsStore.gate = open

        val restore = storeScope.async { service.import(BackupCodec().encode(document)) }
        withTimeout(TIMEOUT_MS) { settingsStore.gateReached.await() }
        try {
            assertEquals("Local", db.sessionDao().sessionById(11)!!.dayTitle)
            assertEquals("Local legacy note", db.sessionDao().sessionById(11)!!.note)
            assertNull(db.restoreMarkerDao().nonce())
        } finally {
            open.complete(Unit)
        }
        withTimeout(TIMEOUT_MS) { restore.await() }

        assertNull(settingsStore.data.first()[key])
        repo.ensureSessionNotesMoved()
        assertEquals("Document note", db.sessionDao().sessionById(11)!!.note)
        assertEquals(mapOf(11L to "Document note"), repo.sessionNotesFlow.first())
    }

    @Test
    fun v13RestoreMovesNotesIntoRoomAndClearsLegacyDataStoreNotes() = runBlocking {
        val codec = BackupCodec()
        val prescription = PrescriptionJson.encode(Prescription(PrescribedTarget.Reps(12)))
        val document = BackupDocument(
            schemaVersion = 13,
            settings = SettingsBackup(190, 33, "NOVICE", weightUnit = "LB", wizardComplete = true,
                sessionNotes = mapOf(11L to "  Restored note  ", 999L to "Unknown session")),
            plans = listOf(PlanBackup(1, "My plan", "GENERATED", 1_000, days = listOf(
                ProgramDayBackup("A", "Day A", "", exercises = listOf(
                    ProgramExerciseBackup(1, "bb_back_squat", true, 3, "12", false, note = "", prescriptionJson = prescription),
                )),
            ))),
            sessions = listOf(SessionBackup(11, "A", "Day A", completedAt = 2_000, bodyweightLb = 190)),
        )
        settingsStore.edit { it[stringPreferencesKey("session_notes")] = """{"11":"Stale local note"}""" }
        val encoded = codec.encode(document).replace(",\"keepToPlan\":null", "")

        service.import(encoded)

        assertTrue(db.programDao().exercise(1)!!.keepToPlan)
        assertEquals("Restored note", db.sessionDao().sessionById(11)!!.note)
        assertTrue(settings.legacySessionNotesFlow.first().isEmpty())
        assertNull(settingsStore.data.first()[stringPreferencesKey("session_notes")])
        repo.ensureSessionNotesMoved()
        assertEquals(mapOf(11L to "Restored note"), repo.sessionNotesFlow.first())
    }
}
