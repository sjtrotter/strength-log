package io.github.sjtrotter.strengthlog.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.CardioSuggestion
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulates the app being killed mid-edit and relaunched (PLAN.md A6, issue #7).
 *
 * There is no API to actually kill and restart a process from inside an
 * instrumented test, so the honest stand-in is: perform mutations through a
 * [TrackerRepository] backed by a real on-disk Room database and a real
 * DataStore file, close both, then open *fresh instances over the same files*
 * — exactly what happens on relaunch — and assert every mutated field is
 * still there. If the app ever regressed to holding unsaved state only in a
 * ViewModel field (the React-prototype bug this hardening exists to prevent),
 * this test would find nothing after reopening.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceProcessDeathTest {

    private val dbName = "process_death_test.db"
    private val prefsName = "process_death_test_prefs"

    private lateinit var db: StrengthDatabase
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var repository: TrackerRepository

    @Before
    fun setUp() {
        cleanUpFiles()
        openSession()
    }

    @After
    fun tearDown() {
        closeSession()
        cleanUpFiles()
    }

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun cleanUpFiles() {
        val context = context()
        context.deleteDatabase(dbName)
        context.preferencesDataStoreFile(prefsName).delete()
    }

    /** (Re)builds the repository over [dbName]/[prefsName] — a fresh Room instance
     *  and a fresh DataStore instance over the same on-disk files, standing in
     *  for a relaunch after process death. */
    private fun openSession() {
        db = Room.databaseBuilder(context(), StrengthDatabase::class.java, dbName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        // DataStore has no close(): its own CoroutineScope is what releases the
        // per-file lock, so each "session" gets one it can cancel before the next
        // session reopens the same file (otherwise DataStore throws — it refuses
        // two live instances over one file, even across GC'd references).
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { context().preferencesDataStoreFile(prefsName) },
        )
        repository = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
    }

    private fun closeSession() {
        db.close()
        dataStoreScope.cancel()
    }

    private val wizardAnswers = WizardAnswers(
        daysPerWeek = 5,
        split = SplitTemplate.UPPER_LOWER,
        anchorScheme = AnchorScheme.BIG_4,
        deadliftVariant = DeadliftVariant.CONVENTIONAL,
        cardio = CardioPrefs(mode = CardioMode.TREADMILL, placement = CardioPlacement.SEPARATE_DAYS, fiveKGoal = false),
        config = LifterConfig(bodyweightLb = 210, age = 33, level = ExperienceLevel.ADVANCED, emphasis = GoalEmphasis.STRENGTH),
        equipment = setOf(Equipment.BARBELL, Equipment.DUMBBELL, Equipment.RACK),
    )

    @Test
    fun mutations_survive_closing_and_reopening_the_database_and_datastore() = runTest {
        val dayId = "A"
        val squat = ProgramExercise(exerciseId = "bb_back_squat", isMain = true, targetSets = 4, repSchemeLabel = "5/5/5/3")
        val row = ProgramExercise(exerciseId = "bb_row", isMain = false, targetSets = 3, repSchemeLabel = "8-12")
        val bench = ProgramExercise(exerciseId = "bb_bench", isMain = false, targetSets = 3, repSchemeLabel = "6-10")
        val day = ProgramDay(
            id = dayId,
            title = "Day A",
            emphasisLine = "Squat-focused lower",
            exercises = listOf(squat, row, bench),
            cardio = CardioSuggestion("Intervals", "5 min easy, then 4x2min hard/easy", hard = true),
        )
        repository.replaceProgram(Program(listOf(day)))

        val squatRowId = db.programDao().exerciseAt(dayId, 0)!!.id
        val rowRowId = db.programDao().exerciseAt(dayId, 1)!!.id

        // A set-weight edit: log an initial weight, then edit it upward.
        repository.updateSets(dayId, squatRowId, Slot.MAIN, listOf(LoggedSet(225.0, 5, SetKind.TOP)))
        repository.updateSets(dayId, squatRowId, Slot.MAIN, listOf(LoggedSet(245.0, 5, SetKind.TOP)))

        // A checkmark toggle: log a set unchecked, then check it off.
        repository.updateSets(dayId, rowRowId, Slot.MAIN, listOf(LoggedSet(95.0, 10, SetKind.WORK, done = false)))
        repository.updateSets(dayId, rowRowId, Slot.MAIN, listOf(LoggedSet(95.0, 10, SetKind.WORK, done = true)))

        // An exercise swap: the bench slot becomes a dumbbell-bench slot.
        repository.swapExercise(dayId, position = 2, newExerciseId = "db_bench")

        // Wizard answers, so a later "edit setup" run can regenerate from them.
        repository.setWizardAnswers(wizardAnswers)
        repository.setWizardComplete(true)

        // --- kill the process: close the DB and DataStore, reopen both ---
        closeSession()
        openSession()

        val program = repository.programFlow.first()
        assertEquals(1, program.days.size)
        val reopenedDay = program.days.single()
        assertEquals("Day A", reopenedDay.title)
        assertEquals(CardioSuggestion("Intervals", "5 min easy, then 4x2min hard/easy", hard = true), reopenedDay.cardio)
        assertEquals(
            listOf("bb_back_squat", "bb_row", "db_bench"),
            reopenedDay.exercises.map { it.exerciseId },
        )

        repository.logFlow(dayId).test {
            val logs = awaitItem().associateBy { it.programExerciseId }
            assertEquals(listOf(LoggedSet(245.0, 5, SetKind.TOP, done = false)), logs.getValue(squatRowId).sets)
            assertEquals(listOf(LoggedSet(95.0, 10, SetKind.WORK, done = true)), logs.getValue(rowRowId).sets)
            // Only the two logged slots persisted; the swapped bench slot was never logged.
            assertEquals(2, logs.size)
        }

        assertEquals(wizardAnswers, repository.wizardAnswersFlow.first())
        assertTrue(repository.wizardCompleteFlow.first())
        assertEquals(dayId, repository.suggestedDayFlow.first())
    }
}
