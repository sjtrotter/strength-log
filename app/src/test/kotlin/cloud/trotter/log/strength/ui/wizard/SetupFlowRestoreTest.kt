package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.data.serialization.planSettings
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.FlakyDataStore
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.FullSnapshot
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.data.serialization.setupAnswers
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.backup.BackupCodec
import cloud.trotter.log.strength.transfer.backup.BackupDocument
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.transfer.backup.PlanBackup
import cloud.trotter.log.strength.transfer.backup.ProgramDayBackup
import cloud.trotter.log.strength.transfer.backup.ProgramExerciseBackup
import cloud.trotter.log.strength.transfer.backup.SettingsBackup
import cloud.trotter.log.strength.transfer.backup.toSnapshot
import cloud.trotter.log.strength.ui.text.BackupErrorKind
import cloud.trotter.log.strength.ui.text.UiText
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The first-run "have a backup?" path end to end: a real SAF-shaped stream into
 * the real [BackupService], the real repository, and back out as either "leave
 * setup" or "stay in it".
 *
 * Unlike [SetupFlowViewModelTest] this fixture runs on real dispatchers —
 * the import genuinely hops to [Dispatchers.IO], so virtual time can't drive
 * it — and every wait is a bounded [waitFor] on the state the UI reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupFlowRestoreTest {

    private lateinit var context: Context
    private lateinit var db: StrengthDatabase
    private lateinit var settingsStore: FlakyDataStore
    private lateinit var settings: SettingsStore
    private var failCompletion = false
    private lateinit var backupRepository: BackupRepository
    private lateinit var repo: TrackerRepository
    private lateinit var journal: RestoreJournal
    private lateinit var journalStore: DataStore<Preferences>

    /** One instance, shared by every ViewModel here, because its restore lock is
     *  the coordination point under test — a per-ViewModel service would hand
     *  each caller its own mutex and coordinate nothing. Hilt provides it as a
     *  `@Singleton` for the same reason. */
    private lateinit var service: BackupService
    private lateinit var storeScope: CoroutineScope

    /** Stands in for the injected app scope. A [SupervisorJob] like the real one
     *  (AppScopeModule): a rejected file fails the restore, and that must not
     *  take the scope — or the next test's stores — down with it. */
    private lateinit var appScope: CoroutineScope
    private val vms = mutableListOf<androidx.lifecycle.ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settingsStore = FlakyDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("wizard-restore-settings", ".preferences_pb")
            },
        )
        settings = SettingsStore(object : DataStore<Preferences> {
            override val data = settingsStore.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failCompletion && transform(androidx.datastore.preferences.core.emptyPreferences())[booleanPreferencesKey("wizard_complete")] == true) {
                    failCompletion = false
                    throw IOException("completion write failed")
                }
                return settingsStore.updateData(transform)
            }
        })
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
        backupRepository = BackupRepository(db, settings, repo.plans, repo.history)
        journalStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("wizard-restore-journal", ".preferences_pb")
        }
        journal = RestoreJournal(journalStore, settings)
        service = BackupService(backupRepository, journal)
    }

    @After
    fun tearDown() {
        // Joined, not just cancelled: on real dispatchers a bare cancel() returns
        // before the import coroutine has let go of Room, and before DataStore's
        // completion handler has released the file.
        runBlocking {
            vms.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            appScope.coroutineContext.job.cancelAndJoin()
            storeScope.coroutineContext.job.cancelAndJoin()
        }
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): SetupFlowViewModel =
        SetupFlowViewModel(repo.plans, handle, context, service, appScope, DeviceWeightUnitProvider { cloud.trotter.log.strength.domain.units.WeightUnit.LB }, settings = settings).also { vms += it }

    /** A minimal but valid backup: one day, one real catalog exercise, and the
     *  wizard flag the test is about. */
    private fun backup(wizardComplete: Boolean) = BackupDocument(
        // A legacy-shaped document: one top-level program.
        schemaVersion = 7,
        settings = SettingsBackup(
            bodyweightLb = 190,
            age = 33,
            level = "NOVICE",
            emphasis = "STRENGTH",
            cardioMode = "NONE",
            cardioPlacement = "NONE",
            fiveKGoal = false,
            daysPerWeek = 3,
            split = "FULL_BODY",
            anchorScheme = "BIG_4",
            deadliftVariant = "SUMO",
            equipment = listOf("BARBELL"),
            weightUnit = "KG",
            wizardComplete = wizardComplete,
        ),
        program = listOf(
            ProgramDayBackup(
                dayId = "A",
                title = "Day A",
                emphasisLine = "Squat-focused",
                exercises = listOf(ProgramExerciseBackup(1, "bb_back_squat", isMain = true, targetSets = 4, supersetExerciseId = null, note = "")),
            ),
        ),
    )

    /** Registers [text] as the bytes behind a SAF-shaped Uri, re-openable so a
     *  retry in the same test reads the same file rather than an empty stream. */
    private fun pickable(text: String): Uri {
        val uri = Uri.parse("content://test.backups/${text.hashCode()}")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        }
        return uri
    }

    private suspend fun SetupFlowViewModel.awaitOffered() =
        waitFor("restore offer") { uiState.first { it.restore.offered } }

    private suspend fun SetupFlowViewModel.startFromGoal() {
        repeat(SetupFlowStateBuilder.steps(uiState.first { it.initialized }.draft).size - 1) { onNext() }
        waitFor("plan preview") { uiState.first { it.plan is PlanPreviewUi.Ready } }
        onNext()
    }

    private suspend fun assertRejectedRestorePreservesFinishRetry(uri: Uri): UiText? {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        vm.awaitOffered()
        // Fail completion after the plan has been published.
        failCompletion = true
        vm.startFromGoal()
        waitFor("finish error") { vm.uiState.first { it.finishError != null && !it.finishInFlight } }
        val created = handle.get<Long>("setup_created_plan_id")
        assertNotNull(created)
        assertEquals(created, db.planDao().allPlans().single().id)
        assertTrue(repo.plans.programFlow.first().days.isNotEmpty())
        assertFalse(settings.wizardCompleteFlow.first())

        repeat(SetupFlowStateBuilder.steps(SetupDraft()).size - 1) { vm.onBack() }
        vm.restoreFromBackup(uri)
        val rejected = waitFor("rejected restore") {
            vm.uiState.first { !it.restore.inFlight && it.restore.error != null }
        }
        assertFalse(rejected.isComplete)
        assertEquals(created, handle.get<Long>("setup_created_plan_id"))
        assertEquals(created, db.planDao().allPlans().single().id)
        assertFalse(settings.wizardCompleteFlow.first())

        vm.startFromGoal()
        val completed = waitFor("completion") { vm.uiState.first { it.isComplete } }

        val plan = db.planDao().allPlans().single()
        assertEquals(created, plan.id)
        assertEquals(created, handle.get<Long>("setup_created_plan_id"))
        assertTrue(db.programDao().daysForPlan(plan.id).isNotEmpty())
        assertEquals(plan.id, settings.selectedPlanIdFlow.first())
        assertTrue(settings.wizardCompleteFlow.first())
        assertNull(completed.finishError)
        return rejected.restore.error
    }

    /** Literal keys preserve the older build's journal format, which carried
     *  plan settings and left no plan in Room when the backup had no program. */
    private suspend fun stagePreviousBuildWithoutProgram(wizardComplete: Boolean): FullSnapshot {
        val document = backup(wizardComplete).copy(program = emptyList())
        val snapshot = document.toSnapshot()
        val recipe = requireNotNull(snapshot.legacyRecipe)
        val legacy = document.settings
        val nonce = "previous-build-restore"
        journalStore.edit { prefs ->
            prefs.clear()
            prefs[intPreferencesKey("bodyweight")] = snapshot.profile.bodyweightLb!!.toInt()
            prefs[intPreferencesKey("age")] = snapshot.profile.age!!
            prefs[stringPreferencesKey("level")] = "INTERMEDIATE"
            prefs[stringPreferencesKey("emphasis")] = requireNotNull(legacy.emphasis)
            prefs[stringPreferencesKey("cardio_mode")] = recipe.cardioMode.name
            prefs[stringPreferencesKey("cardio_placement")] = requireNotNull(legacy.cardioPlacement)
            prefs[booleanPreferencesKey("five_k")] = recipe.fiveKGoal
            prefs[intPreferencesKey("days_per_week")] = requireNotNull(legacy.daysPerWeek)
            prefs[stringPreferencesKey("split")] = requireNotNull(legacy.split)
            prefs[stringPreferencesKey("anchor_scheme")] = requireNotNull(legacy.anchorScheme)
            prefs[stringPreferencesKey("deadlift_variant")] = requireNotNull(legacy.deadliftVariant)
            prefs[stringSetPreferencesKey("equipment")] = recipe.equipment.map { it.name }.toSet()
            prefs[stringPreferencesKey("weight_unit")] = snapshot.unit.name
            prefs[booleanPreferencesKey("wizard_complete")] = snapshot.wizardComplete
            prefs[stringPreferencesKey("restore_nonce")] = nonce
        }
        db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = nonce))
        return snapshot
    }

    @Test
    fun restoringACompletedBackupLeavesSetup() = runBlocking {
        val uri = pickable(BackupCodec().encode(backup(wizardComplete = true)))
        val vm = newViewModel()
        vm.awaitOffered()

        vm.restoreFromBackup(uri)

        // isComplete is what AppNavHost's SetupFlowRoute watches to call onFinished.
        waitFor("completion") { vm.uiState.first { it.isComplete } }
        assertTrue(settings.wizardCompleteFlow.first())
        assertEquals(190.0, settings.lifterProfileFlow.first().bodyweightLb!!, 0.0)
        assertEquals(1, repo.plans.programFlow.first().days.size)
    }

    @Test
    fun restoringV8WithTwoPlansKeepsTheirDaysAndTheSecondSelection() = runBlocking {
        val codec = BackupCodec()
        val legacy = backup(wizardComplete = true)
        val generatedDay = legacy.program.single()
        val authoredDay = generatedDay.copy(
            title = "Test A",
            emphasisLine = "Bench-focused",
            exercises = generatedDay.exercises.map { it.copy(id = 2, exerciseId = "bb_bench") },
        )
        val document = legacy.copy(
            schemaVersion = 8,
            settings = legacy.settings.copy(selectedPlanId = 42),
            program = emptyList(),
            plans = listOf(
                PlanBackup(7, "Maintenance", "GENERATED", 1_000, days = listOf(generatedDay)),
                PlanBackup(42, "Test prep", "AUTHORED", 2_000, days = listOf(authoredDay)),
            ),
        )
        val uri = pickable(codec.encode(document))
        val vm = newViewModel()
        vm.awaitOffered()

        vm.restoreFromBackup(uri)

        val state = waitFor("completion") { vm.uiState.first { it.isComplete } }
        assertNull(state.restore.error)
        assertTrue(settings.wizardCompleteFlow.first())
        val plans = db.planDao().allPlans()
        assertEquals(listOf(7L, 42L), plans.map { it.id })
        assertEquals(listOf("Maintenance", "Test prep"), plans.map { it.name })
        assertEquals(listOf("GENERATED", "AUTHORED"), plans.map { it.origin })
        assertEquals(listOf("A" to "Day A"), db.programDao().daysForPlan(7).map { it.dayId to it.title })
        assertEquals(listOf("A" to "Test A"), db.programDao().daysForPlan(42).map { it.dayId to it.title })
        assertEquals(42L, settings.selectedPlanIdFlow.first())
        assertEquals(listOf("Test A"), repo.plans.programFlow.first().days.map { it.title })
    }

    @Test
    fun restoringAMidFirstRunBackupStaysInSetupWithTheRestoredUnit() = runBlocking {
        // wizardComplete=false means the backup was taken part-way through a first
        // run: leaving for the day screen would strand it on an empty program.
        val uri = pickable(BackupCodec().encode(backup(wizardComplete = false)))
        val vm = newViewModel()
        vm.awaitOffered()

        vm.restoreFromBackup(uri)

        // Wait for the restored draft and progress to settle.
        val state = waitFor("reseeded draft") {
            vm.uiState.first { !it.restore.inFlight && it.draft.unit == WeightUnit.KG }
        }
        assertFalse(state.isComplete)
        assertFalse(state.restore.inFlight)
        assertEquals(null, state.restore.error)
        assertEquals(190.0, settings.lifterProfileFlow.first().bodyweightLb!!, 0.0)
        assertEquals(33, settings.lifterProfileFlow.first().age)
        assertFalse(settings.wizardCompleteFlow.first())
    }

    @Test
    fun aMidFirstRunBackupWithoutAProgramReseedsTheDraftAtGoal() = runBlocking {
        val document = backup(wizardComplete = false).copy(program = emptyList())
        val uri = pickable(BackupCodec().encode(document))
        val vm = newViewModel()
        vm.awaitOffered()

        vm.restoreFromBackup(uri)

        val state = waitFor("reseeded draft") {
            vm.uiState.first { !it.restore.inFlight && it.draft.unit == WeightUnit.KG }
        }
        assertFalse(state.isComplete)
        assertEquals(null, state.restore.error)
        assertEquals(190.0, settings.lifterProfileFlow.first().bodyweightLb!!, 0.0)
        assertEquals(SetupStep.GOAL, state.step)
        assertEquals(SetupAnswers(unit = WeightUnit.KG), SetupFlowStateBuilder.answers(state.draft))
        assertEquals(document.toSnapshot().profile, settings.lifterProfileFlow.first())
        assertEquals(1, db.planDao().allPlans().size)
        assertTrue(repo.plans.programFlow.first().days.isEmpty())
        assertFalse(settings.wizardCompleteFlow.first())
        val exported = service.export()
        vm.setDays(6)
        vm.setUnit(WeightUnit.LB)
        waitFor("reseeded draft") { vm.uiState.first { it.draft.unit == WeightUnit.LB } }
        vm.restoreFromBackup(pickable(exported))
        val reseeded = waitFor("reseeded draft") {
            vm.uiState.first { !it.restore.inFlight && it.draft.unit == WeightUnit.KG }
        }
        assertEquals(SetupDraft(unit = WeightUnit.KG), reseeded.draft)
        assertEquals(document.toSnapshot().profile, settings.lifterProfileFlow.first())
        assertEquals(document.toSnapshot().profile, backupRepository.exportSnapshot().profile)
    }

    @Test
    fun anEmptyMidSetupBackupAfterAFailedCompletionCreatesOnStart() = runBlocking {
        val document = backup(wizardComplete = false).copy(program = emptyList())
        val uri = pickable(BackupCodec().encode(document))
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        vm.awaitOffered()
        // Fail completion after the plan has been published.
        failCompletion = true
        vm.startFromGoal()
        waitFor("finish error") { vm.uiState.first { it.finishError != null && !it.finishInFlight } }
        val created = handle.get<Long>("setup_created_plan_id")
        assertNotNull(created)
        assertTrue(repo.plans.programFlow.first().days.isNotEmpty())
        assertFalse(settings.wizardCompleteFlow.first())

        repeat(SetupFlowStateBuilder.steps(SetupDraft()).size - 1) { vm.onBack() }
        vm.restoreFromBackup(uri)
        val restored = waitFor("reseeded draft") {
            vm.uiState.first { !it.restore.inFlight && it.draft.unit == WeightUnit.KG }
        }
        assertFalse(restored.isComplete)
        assertNull(restored.restore.error)
        assertNull(handle.get<Long>("setup_created_plan_id"))
        assertTrue(repo.plans.programFlow.first().days.isEmpty())
        assertFalse(settings.wizardCompleteFlow.first())

        vm.startFromGoal()
        val completed = waitFor("completion") { vm.uiState.first { it.isComplete } }

        val plan = db.planDao().allPlans().single()
        assertEquals(plan.id, handle.get<Long>("setup_created_plan_id"))
        assertTrue(db.programDao().daysForPlan(plan.id).isNotEmpty())
        assertEquals(plan.id, settings.selectedPlanIdFlow.first())
        assertEquals(3, repo.plans.programFlow.first().strengthDays.size)
        assertTrue(settings.wizardCompleteFlow.first())
        assertNull(completed.finishError)
    }

    @Test
    fun restoringAnInvalidBackupAfterFailedCompletionReusesTheCreatedPlanOnStart() = runBlocking {
        val error = assertRejectedRestorePreservesFinishRetry(pickable("{ not a backup at all"))

        assertTrue(error is UiText.BackupError && error.kind == BackupErrorKind.BACKUP_MALFORMED)
    }

    @Test
    fun restoringAnUnreadableBackupAfterFailedCompletionReusesTheCreatedPlanOnStart() = runBlocking {
        val uri = Uri.parse("content://test.backups/unreadable")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            object : InputStream() {
                override fun read(): Int = throw IOException("backup is unreadable")
            }
        }

        val error = assertRejectedRestorePreservesFinishRetry(uri)

        assertEquals(UiText.FileAccessFailure("backup is unreadable"), error)
    }

    @Test
    fun aFileThatIsNotABackupShowsAnErrorAndLeavesTheDeviceUntouched() = runBlocking {
        val uri = pickable("{ not a backup at all")
        val vm = newViewModel()
        vm.awaitOffered()

        vm.restoreFromBackup(uri)

        val state = waitFor("restore error") { vm.uiState.first { it.restore.error != null } }
        assertNotNull(state.restore.error)
        assertFalse(state.restore.inFlight)
        assertFalse(state.isComplete)
        assertFalse(settings.wizardCompleteFlow.first())
        assertTrue(repo.plans.programFlow.first().days.isEmpty())
    }

    @Test
    fun startingBeforeStartupRecoveryStepsAsideForAStagedCompletedSetup() = runBlocking {
        val staged = stagePreviousBuildWithoutProgram(wizardComplete = true)
        assertTrue(db.planDao().allPlans().isEmpty())
        val vm = newViewModel()
        vm.awaitOffered()
        vm.setDays(6)

        // No startup reconcile has run: finish must settle the journal first.
        vm.startFromGoal()
        waitFor("completion") { vm.uiState.first { it.isComplete } }

        val restoredPlan = db.planDao().allPlans().single()
        assertEquals(PlanEntity.LEGACY_ID, restoredPlan.id)
        assertNull(restoredPlan.recipeJson)
        assertEquals(requireNotNull(staged.legacyRecipe).equipment, restoredPlan.planSettings.equipment)
        assertEquals(staged.profile, settings.lifterProfileFlow.first())
        assertTrue("wizardComplete must retain the staged value", settings.wizardCompleteFlow.first())
        assertTrue(repo.plans.programFlow.first().days.isEmpty())
        assertNull(db.restoreMarkerDao().nonce())
        assertFalse(service.reconcilePendingRestore())
    }

    @Test
    fun startingBeforeStartupRecoveryCreatesAPlanMatchingItsRecipe() = runBlocking {
        stagePreviousBuildWithoutProgram(wizardComplete = false)
        assertTrue(db.planDao().allPlans().isEmpty())
        val vm = newViewModel()
        vm.awaitOffered()
        vm.setDays(6)

        vm.startFromGoal()
        waitFor("completion") { vm.uiState.first { it.isComplete } }

        // A later startup reconcile must not overwrite the six-day recipe with
        // the staged three-day answers after the wizard has generated its plan.
        assertFalse(service.reconcilePendingRestore())
        val plan = db.planDao().allPlans().single()
        val answers = requireNotNull(plan.setupAnswers(WeightUnit.LB))
        val program = repo.plans.programFlow.first()
        assertEquals(6, answers.days)
        assertEquals(Planner.plan(answers).program, program)
        assertEquals(SetupRecipeJson.encode(answers), plan.recipeJson)
        assertTrue(settings.wizardCompleteFlow.first())
        assertNull(db.restoreMarkerDao().nonce())
    }

    @Test
    fun aRecoveryFailureInStartShowsAnErrorAndCanBeRetried() = runBlocking {
        settingsStore.failOnUpdate = 1
        val failure = runCatching { service.import(BackupCodec().encode(backup(wizardComplete = true))) }.exceptionOrNull()
        assertTrue(failure is RestoreInterruption.SettingsPending)
        val vm = newViewModel()
        vm.awaitOffered()
        vm.setDays(6)
        val before = backupRepository.exportSnapshot()
        settingsStore.failOnUpdate = 2 // the recovery's settings write

        vm.startFromGoal()
        val state = waitFor("finish error") { vm.uiState.first { it.finishError != null } }

        assertEquals(SetupStep.PLAN, state.step)
        assertTrue(state.finishError is UiText.FileAccessFailure)
        assertNull(state.restore.error)
        assertFalse(state.restore.inFlight)
        assertFalse(state.isComplete)
        assertFalse(settings.wizardCompleteFlow.first())
        assertEquals(before, backupRepository.exportSnapshot())
        assertNotNull(db.restoreMarkerDao().nonce())

        vm.onNext()
        val completed = waitFor("completion") { vm.uiState.first { it.isComplete } }
        assertNull(completed.finishError)
        assertNull(completed.restore.error)
        assertTrue(settings.wizardCompleteFlow.first())
        assertEquals(listOf("A"), repo.plans.programFlow.first().days.map { it.id })
        assertEquals(190.0, settings.lifterProfileFlow.first().bodyweightLb!!, 0.0)
        assertNull(db.restoreMarkerDao().nonce())
    }

    @Test
    fun startWaitsForAnInFlightReconcileAndThenStepsAside() = runBlocking {
        // The check/use race in full. Leave the device in the split state a killed
        // restore produces — data in Room, settings still the previous ones — so
        // `wizardComplete` reads false and the app opens on the wizard, then let
        // the startup reconciliation land while the wizard is being finished.
        settingsStore.failOnUpdate = 1
        runCatching { service.import(BackupCodec().encode(backup(wizardComplete = true))) }
        assertEquals(listOf("A"), repo.plans.programFlow.first().days.map { it.id })
        assertFalse("the flag the wizard reads is still the old one", settings.wizardCompleteFlow.first())

        val vm = newViewModel()
        vm.awaitOffered() // the wizard has latched firstRun = true

        // Park the reconcile inside its settings write, holding the restore lock.
        val open = CompletableDeferred<Unit>()
        // Import failed on update 1; reconciliation is the next settings write.
        settingsStore.gateOnUpdate = 2
        settingsStore.gate = open
        val reconcile = storeScope.async { service.reconcilePendingRestore() }
        waitFor("settings gate") { settingsStore.gateReached.await() }

        // Finish the wizard while it is parked. Outside the lock this reads
        // wizardComplete=false and generates over the restored program.
        vm.startFromGoal()
        delay(200)
        assertFalse("finish must wait its turn, not read a flag mid-flip", vm.uiState.value.isComplete)

        open.complete(Unit)
        assertTrue("the reconcile had a payload to replay", waitFor("reconcile") { reconcile.await() })
        waitFor("completion") { vm.uiState.first { it.isComplete } }

        assertEquals(
            "the restored program must survive the wizard finishing over it",
            listOf("A"),
            repo.plans.programFlow.first().days.map { it.id },
        )
        assertTrue(settings.wizardCompleteFlow.first())
        assertEquals(190.0, settings.lifterProfileFlow.first().bodyweightLb!!, 0.0)
    }

    /** Waits up to [TIMEOUT_MS] for [what]; on expiry fails with every thread's stack, so a CI timeout shows where it
     *  stuck. */
    private suspend fun <T : Any> waitFor(what: String, block: suspend () -> T): T =
        withTimeoutOrNull(TIMEOUT_MS) { block() } ?: throw AssertionError("No $what within $TIMEOUT_MS ms\n" +
            Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                "${thread.name} (${thread.state})\n" + stack.joinToString("\n") { "    at $it" }
            })

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
