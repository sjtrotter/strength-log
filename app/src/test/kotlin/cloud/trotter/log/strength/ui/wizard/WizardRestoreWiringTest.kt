package cloud.trotter.log.strength.ui.wizard

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
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.transfer.backup.BackupCodec
import cloud.trotter.log.strength.transfer.backup.BackupDocument
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.transfer.backup.ProgramDayBackup
import cloud.trotter.log.strength.transfer.backup.ProgramExerciseBackup
import cloud.trotter.log.strength.transfer.backup.SettingsBackup
import cloud.trotter.log.strength.transfer.backup.toSnapshot
import cloud.trotter.log.strength.ui.text.UiText
import java.io.ByteArrayInputStream
import java.io.File
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The first-run "have a backup?" path end to end: a real SAF-shaped stream into
 * the real [BackupService], the real repository, and back out as either "leave
 * the wizard" or "stay in it".
 *
 * Unlike [WizardViewModelWiringTest] this fixture runs on real dispatchers —
 * the import genuinely hops to [Dispatchers.IO], so virtual time can't drive
 * it — and every wait is a bounded [withTimeout] on the state the UI reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WizardRestoreWiringTest {

    private lateinit var context: Context
    private lateinit var db: StrengthDatabase
    private lateinit var settingsStore: FlakyDataStore
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var journal: RestoreJournal
    private lateinit var journalStore: DataStore<Preferences>

    /** One instance, shared by every ViewModel here, because its restore lock is
     *  the coordination point under test — a per-ViewModel service would hand
     *  each caller its own mutex and coordinate nothing (#172). Hilt provides it
     *  as a `@Singleton` for the same reason. */
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
        settings = SettingsStore(settingsStore)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
        journalStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("wizard-restore-journal", ".preferences_pb")
        }
        journal = RestoreJournal(journalStore, settings)
        service = BackupService(repo, journal)
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

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): WizardViewModel =
        WizardViewModel(repo, handle, context, service, appScope, DeviceWeightUnitProvider { cloud.trotter.log.strength.domain.units.WeightUnit.LB }).also { vms += it }

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
                exercises = listOf(ProgramExerciseBackup(1, "bb_back_squat", true, 4, "5/5/5/3", true, null, "")),
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

    private suspend fun WizardViewModel.awaitOffered() =
        withTimeout(TIMEOUT_MS) { uiState.first { it.restore.offered } }

    /** Literal keys preserve the older build's journal format, which carried
     *  plan settings and left no plan in Room when the backup had no program. */
    private suspend fun stagePreviousBuildWithoutProgram(wizardComplete: Boolean): WizardAnswers {
        val snapshot = backup(wizardComplete).copy(program = emptyList()).toSnapshot()
        val answers = snapshot.answers
        val nonce = "previous-build-restore"
        journalStore.edit { prefs ->
            prefs.clear()
            prefs[intPreferencesKey("bodyweight")] = answers.config.bodyweightLb
            prefs[intPreferencesKey("age")] = answers.config.age
            prefs[stringPreferencesKey("level")] = answers.config.level.name
            prefs[stringPreferencesKey("emphasis")] = answers.config.emphasis.name
            prefs[stringPreferencesKey("cardio_mode")] = answers.cardio.mode.name
            prefs[stringPreferencesKey("cardio_placement")] = answers.cardio.placement.name
            prefs[booleanPreferencesKey("five_k")] = answers.cardio.fiveKGoal
            prefs[intPreferencesKey("days_per_week")] = answers.daysPerWeek
            prefs[stringPreferencesKey("split")] = answers.split.name
            prefs[stringPreferencesKey("anchor_scheme")] = answers.anchorScheme.name
            prefs[stringPreferencesKey("deadlift_variant")] = answers.deadliftVariant.name
            prefs[stringSetPreferencesKey("equipment")] = answers.equipment.map { it.name }.toSet()
            prefs[stringPreferencesKey("weight_unit")] = snapshot.unit.name
            prefs[booleanPreferencesKey("wizard_complete")] = snapshot.wizardComplete
            prefs[stringPreferencesKey("restore_nonce")] = nonce
        }
        db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = nonce))
        return answers
    }

    @Test
    fun restoringACompletedBackup_leavesTheWizardTheSameWayFinishDoes() = runBlocking {
        val uri = pickable(BackupCodec().encode(backup(wizardComplete = true)))
        val vm = newViewModel()
        vm.awaitOffered()

        vm.restoreFromBackup(uri)

        // isComplete is what AppNavHost's WizardRoute watches to call onFinished.
        withTimeout(TIMEOUT_MS) { vm.uiState.first { it.isComplete } }
        assertTrue(repo.wizardCompleteFlow.first())
        assertEquals(190, repo.wizardAnswersFlow.first().config.bodyweightLb)
        assertEquals(1, repo.programFlow.first().days.size)
    }

    @Test
    fun restoringAMidFirstRunBackup_staysInTheWizardOnTheRestoredAnswers() = runBlocking {
        // wizardComplete=false means the backup was taken part-way through a first
        // run: leaving for the day screen would strand it on an empty program.
        val uri = pickable(BackupCodec().encode(backup(wizardComplete = false)))
        val vm = newViewModel()
        vm.awaitOffered()

        vm.restoreFromBackup(uri)

        // The restore lands field by field — the answers are pushed into the
        // SavedStateHandle one key at a time and only then is inFlight cleared — so
        // wait for the settled state and assert on *that*, not on whatever the
        // StateFlow happens to be holding a moment after the first key changes.
        val state = withTimeout(TIMEOUT_MS) {
            vm.uiState.first { !it.restore.inFlight && it.answers.daysPerWeek == 3 }
        }
        assertFalse(state.isComplete)
        assertFalse(state.restore.inFlight)
        assertEquals(null, state.restore.error)
        assertEquals(190, state.answers.config.bodyweightLb)
        assertEquals(33, state.answers.config.age)
        assertFalse(repo.wizardCompleteFlow.first())
    }

    @Test
    fun restoringAMidFirstRunBackupWithoutAProgram_keepsTheRestoredAnswers() = runBlocking {
        val document = backup(wizardComplete = false).copy(program = emptyList())
        val uri = pickable(BackupCodec().encode(document))
        val vm = newViewModel()
        vm.awaitOffered()

        vm.restoreFromBackup(uri)

        val state = withTimeout(TIMEOUT_MS) {
            vm.uiState.first { !it.restore.inFlight && it.answers.daysPerWeek == 3 }
        }
        assertFalse(state.isComplete)
        assertEquals(null, state.restore.error)
        assertEquals(190, state.answers.config.bodyweightLb)
        assertEquals("STRENGTH", state.answers.config.emphasis.name)
        assertEquals(state.answers, repo.wizardAnswersFlow.first())
        assertEquals(1, db.planDao().allPlans().size)
        assertTrue(repo.programFlow.first().days.isEmpty())
        assertFalse(repo.wizardCompleteFlow.first())
    }

    @Test
    fun restoringAnEmptyMidSetupBackupAfterFailedCompletion_createsAProgramOnFinish() = runBlocking {
        val document = backup(wizardComplete = false).copy(program = emptyList())
        val uri = pickable(BackupCodec().encode(document))
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        vm.awaitOffered()
        // Update 1 retires legacy settings during initialization; Finish writes
        // global lifter, selection, unit, then completion in updates 2 through 5.
        settingsStore.failOnUpdate = 5
        repeat(WizardStep.entries.size) { vm.onNext() }
        withTimeout(TIMEOUT_MS) { vm.uiState.first { it.finishError != null && !it.finishInFlight } }
        val created = handle.get<Long>("wizard_created_plan_id")
        assertNotNull(created)
        assertTrue(repo.programFlow.first().days.isNotEmpty())
        assertFalse(repo.wizardCompleteFlow.first())

        repeat(WizardStep.ROTATION.ordinal) { vm.onBack() }
        vm.restoreFromBackup(uri)
        val restored = withTimeout(TIMEOUT_MS) {
            vm.uiState.first { !it.restore.inFlight && it.answers.daysPerWeek == 3 }
        }
        assertFalse(restored.isComplete)
        assertNull(restored.restore.error)
        assertNull(handle.get<Long>("wizard_created_plan_id"))
        assertTrue(repo.programFlow.first().days.isEmpty())
        assertFalse(repo.wizardCompleteFlow.first())

        repeat(WizardStep.entries.size) { vm.onNext() }
        val completed = withTimeout(TIMEOUT_MS) { vm.uiState.first { it.isComplete } }

        val plan = db.planDao().allPlans().single()
        assertEquals(plan.id, handle.get<Long>("wizard_created_plan_id"))
        assertTrue(db.programDao().daysForPlan(plan.id).isNotEmpty())
        assertEquals(plan.id, settings.selectedPlanIdFlow.first())
        assertEquals(3, repo.programFlow.first().strengthDays.size)
        assertTrue(repo.wizardCompleteFlow.first())
        assertNull(completed.finishError)
    }

    @Test
    fun aFileThatIsNotABackup_showsAnErrorAndLeavesTheDeviceUntouched() = runBlocking {
        val uri = pickable("{ not a backup at all")
        val vm = newViewModel()
        vm.awaitOffered()

        vm.restoreFromBackup(uri)

        val state = withTimeout(TIMEOUT_MS) { vm.uiState.first { it.restore.error != null } }
        assertNotNull(state.restore.error)
        assertFalse(state.restore.inFlight)
        assertFalse(state.isComplete)
        assertFalse(repo.wizardCompleteFlow.first())
        assertTrue(repo.programFlow.first().days.isEmpty())
    }

    @Test
    fun finishingBeforeStartupRecovery_stepsAsideForTheStagedCompletedWizard() = runBlocking {
        val stagedAnswers = stagePreviousBuildWithoutProgram(wizardComplete = true)
        assertTrue(db.planDao().allPlans().isEmpty())
        val vm = newViewModel()
        vm.awaitOffered()
        vm.setDaysPerWeek(6)

        // No startup reconcile has run: finish must settle the journal first.
        repeat(WizardStep.entries.size) { vm.onNext() }
        withTimeout(TIMEOUT_MS) { vm.uiState.first { it.isComplete } }

        val restoredPlan = db.planDao().allPlans().single()
        assertEquals(PlanEntity.LEGACY_ID, restoredPlan.id)
        assertEquals(PlanRecipeJson.encode(stagedAnswers), restoredPlan.recipeJson)
        assertEquals(stagedAnswers, repo.wizardAnswersFlow.first())
        assertTrue("wizardComplete must retain the staged value", repo.wizardCompleteFlow.first())
        assertTrue(repo.programFlow.first().days.isEmpty())
        assertNull(db.restoreMarkerDao().nonce())
        assertFalse(service.reconcilePendingRestore())
    }

    @Test
    fun finishingBeforeStartupRecovery_generatesAProgramMatchingItsRecipe() = runBlocking {
        stagePreviousBuildWithoutProgram(wizardComplete = false)
        assertTrue(db.planDao().allPlans().isEmpty())
        val vm = newViewModel()
        vm.awaitOffered()
        vm.setDaysPerWeek(6)
        vm.setCardioMode(CardioMode.NONE)
        vm.setCardioPlacement(CardioPlacement.NONE)

        repeat(WizardStep.entries.size) { vm.onNext() }
        withTimeout(TIMEOUT_MS) { vm.uiState.first { it.isComplete } }

        // A later startup reconcile must not overwrite the six-day recipe with
        // the staged three-day answers after the wizard has generated its plan.
        assertFalse(service.reconcilePendingRestore())
        val answers = repo.wizardAnswersFlow.first()
        val program = repo.programFlow.first()
        assertEquals(6, answers.daysPerWeek)
        assertEquals(6, program.days.size)
        assertEquals(ProgramGenerator.generate(answers).program, program)
        assertEquals(PlanRecipeJson.encode(answers), db.planDao().allPlans().single().recipeJson)
        assertTrue(repo.wizardCompleteFlow.first())
        assertNull(db.restoreMarkerDao().nonce())
    }

    @Test
    fun aRecoveryFailureInFinish_showsAnErrorWithoutWritingAndCanBeRetried() = runBlocking {
        settingsStore.failOnUpdate = 1
        val failure = runCatching { service.import(BackupCodec().encode(backup(wizardComplete = true))) }.exceptionOrNull()
        assertTrue(failure is RestoreInterruption.SettingsPending)
        val vm = newViewModel()
        vm.awaitOffered()
        vm.setDaysPerWeek(6)
        val before = repo.exportSnapshot()
        settingsStore.failOnUpdate = 2 // the recovery's settings write

        repeat(WizardStep.entries.size) { vm.onNext() }
        val state = withTimeout(TIMEOUT_MS) { vm.uiState.first { it.finishError != null } }

        assertEquals(WizardStep.ROTATION, state.step)
        assertTrue(state.finishError is UiText.FileAccessFailure)
        assertNull(state.restore.error)
        assertFalse(state.restore.inFlight)
        assertFalse(state.isComplete)
        assertFalse(repo.wizardCompleteFlow.first())
        assertEquals(before, repo.exportSnapshot())
        assertNotNull(db.restoreMarkerDao().nonce())

        vm.onNext()
        val completed = withTimeout(TIMEOUT_MS) { vm.uiState.first { it.isComplete } }
        assertNull(completed.finishError)
        assertNull(completed.restore.error)
        assertTrue(repo.wizardCompleteFlow.first())
        assertEquals(listOf("A"), repo.programFlow.first().days.map { it.id })
        assertEquals(190, repo.configFlow.first().bodyweightLb)
        assertNull(db.restoreMarkerDao().nonce())
    }

    @Test
    fun finishingTheWizardWaitsForAnInFlightReconcileAndThenStepsAside() = runBlocking {
        // The check/use race in full (#172). Leave the device in the split state a
        // killed restore produces — data in Room, settings still the old ones —
        // so `wizardComplete` reads false and the app opens on the wizard, then
        // let the startup reconciliation land while the wizard is being finished.
        settingsStore.failOnUpdate = 1
        runCatching { service.import(BackupCodec().encode(backup(wizardComplete = true))) }
        assertEquals(listOf("A"), repo.programFlow.first().days.map { it.id })
        assertFalse("the flag the wizard reads is still the old one", repo.wizardCompleteFlow.first())

        val vm = newViewModel()
        vm.awaitOffered() // the wizard has latched firstRun = true

        // Park the reconcile inside its settings write, holding the restore lock.
        val open = CompletableDeferred<Unit>()
        // Update 1 failed in import; update 2 retired the empty legacy keys
        // when the wizard's initial answers read passed through the gate.
        settingsStore.gateOnUpdate = 3
        settingsStore.gate = open
        val reconcile = storeScope.async { service.reconcilePendingRestore() }
        withTimeout(TIMEOUT_MS) { settingsStore.gateReached.await() }

        // Finish the wizard while it is parked. Outside the lock this reads
        // wizardComplete=false and generates over the restored program.
        repeat(WizardStep.entries.size - 1) { vm.onNext() }
        vm.onNext()
        delay(200)
        assertFalse("finish must wait its turn, not read a flag mid-flip", vm.uiState.value.isComplete)

        open.complete(Unit)
        assertTrue("the reconcile had a payload to replay", withTimeout(TIMEOUT_MS) { reconcile.await() })
        withTimeout(TIMEOUT_MS) { vm.uiState.first { it.isComplete } }

        assertEquals(
            "the restored program must survive the wizard finishing over it",
            listOf("A"),
            repo.programFlow.first().days.map { it.id },
        )
        assertTrue(repo.wizardCompleteFlow.first())
        assertEquals(190, repo.configFlow.first().bodyweightLb)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
