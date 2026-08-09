package cloud.trotter.log.strength.ui.wizard

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.transfer.backup.BackupCodec
import cloud.trotter.log.strength.transfer.backup.BackupDocument
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.transfer.backup.ProgramDayBackup
import cloud.trotter.log.strength.transfer.backup.ProgramExerciseBackup
import cloud.trotter.log.strength.transfer.backup.SettingsBackup
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var journal: RestoreJournal
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
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("wizard-restore-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
        journal = RestoreJournal(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("wizard-restore-journal", ".preferences_pb")
            },
            settings,
        )
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

    private fun newViewModel(): WizardViewModel =
        WizardViewModel(repo, SavedStateHandle(), context, BackupService(repo, journal), appScope)
            .also { vms += it }

    /** A minimal but valid backup: one day, one real catalog exercise, and the
     *  wizard flag the test is about. */
    private fun backup(wizardComplete: Boolean) = BackupDocument(
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

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
