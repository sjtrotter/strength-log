package io.github.sjtrotter.strengthlog.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.library.GoalSource
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.CardioSuggestion
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the A6 persistence guarantee (PLAN.md, issue #7): every mutation made
 * through [TrackerRepository] is committed to Room/DataStore immediately, so
 * nothing the user entered exists only in memory. Mutations are written through
 * a repository backed by a real on-disk Room database and a real DataStore
 * file; both are then closed and reopened as *fresh instances over the same
 * files*, and every mutated field must still be there. If the app ever
 * regressed to holding unsaved truth in a ViewModel field (the React-prototype
 * bug this hardening exists to prevent), the reopened instances would come up
 * empty.
 *
 * Honesty note on fidelity: `db.close()` is a *clean* shutdown (WAL
 * checkpointed, files flushed) — strictly cleaner than a real process kill, so
 * this test does not exercise the crash path where SQLite replays an
 * uncheckpointed `-wal` file on next open. For *committed* transactions the
 * durability guarantee is the same either way (that replay is SQLite's job,
 * not ours); what this test rules out is the application-level bug of never
 * committing at all.
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
        runBlocking { closeSession() }
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
     *  for a relaunch. */
    private fun openSession() {
        db = Room.databaseBuilder(context(), StrengthDatabase::class.java, dbName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        // DataStore has no close(): cancelling the CoroutineScope it was created
        // with is what releases the per-file lock, so each "session" gets one.
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

    /** Joining the cancelled scope matters: DataStore only marks the file
     *  inactive once its scope has fully completed, and reopening before that
     *  races an IllegalStateException ("multiple DataStores active"). */
    private suspend fun closeSession() {
        db.close()
        dataStoreScope.coroutineContext.job.cancelAndJoin()
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
    fun every_mutation_survives_closing_and_reopening_the_database_and_datastore() = runTest {
        // --- settings: wizard answers, completion flag, display unit ----------
        repository.setWizardAnswers(wizardAnswers)
        repository.setWizardComplete(true)
        repository.setUnit(WeightUnit.KG)

        // --- a user-created exercise ------------------------------------------
        val customId = repository.addCustomExercise(
            name = "Cable Hack Squat",
            pattern = MovementPattern.SQUAT_BILATERAL,
            equipment = listOf(Equipment.MACHINE, Equipment.CABLE),
            perHand = false,
            goalStartLb = 80.0,
        )

        // --- the program -------------------------------------------------------
        val squat = ProgramExercise(
            exerciseId = "bb_back_squat",
            isMain = true,
            targetSets = 4,
            repSchemeLabel = "5/5/5/3",
            hasWarmupHint = true,
            note = "belt on top set",
        )
        val row = ProgramExercise(exerciseId = "bb_row", targetSets = 3, repSchemeLabel = "8-12")
        val bench = ProgramExercise(exerciseId = "bb_bench", targetSets = 3, repSchemeLabel = "6-10")
        val dayA = ProgramDay(
            id = "A",
            title = "Day A",
            emphasisLine = "Squat-focused lower",
            exercises = listOf(squat, row, bench),
            cardio = CardioSuggestion("Intervals", "5 min easy, then 4x2min hard/easy", hard = true),
        )
        val dayB = ProgramDay(
            id = "B",
            title = "Day B",
            emphasisLine = "Bench-focused upper",
            exercises = listOf(ProgramExercise(exerciseId = "bb_bench", isMain = true, targetSets = 4)),
            cardio = null,
        )
        repository.replaceProgram(Program(listOf(dayA, dayB)))

        val squatRowId = db.programDao().exerciseAt("A", 0)!!.id
        val rowRowId = db.programDao().exerciseAt("A", 1)!!.id

        // --- a completed workout: log, then DONE — advance ---------------------
        repository.updateSets("A", squatRowId, Slot.MAIN, listOf(LoggedSet(245.0, 5, SetKind.TOP, done = true)))
        repository.updateSets("A", rowRowId, Slot.MAIN, listOf(LoggedSet(95.0, 10, SetKind.WORK, done = true)))
        repository.advanceDay("A") // appends history, clears A's checks, rotation -> B

        // --- mid-edit state after that session ---------------------------------
        // A set-weight edit: write once, then edit upward.
        repository.updateSets("A", squatRowId, Slot.MAIN, listOf(LoggedSet(250.0, 5, SetKind.TOP)))
        repository.updateSets("A", squatRowId, Slot.MAIN, listOf(LoggedSet(255.0, 5, SetKind.TOP)))
        // A checkmark toggle: unchecked, then checked.
        repository.updateSets("A", rowRowId, Slot.MAIN, listOf(LoggedSet(100.0, 10, SetKind.WORK, done = false)))
        repository.updateSets("A", rowRowId, Slot.MAIN, listOf(LoggedSet(100.0, 10, SetKind.WORK, done = true)))
        // An exercise swap: the bench slot becomes a dumbbell-bench slot.
        repository.swapExercise("A", position = 2, newExerciseId = "db_bench")

        // Snapshot the live logs (including each slot's checkDate) as the last
        // thing written before the "kill".
        val logsBeforeKill = repository.logFlow("A").first().sortedBy { it.programExerciseId }
        assertEquals(2, logsBeforeKill.size)

        // --- kill the process: close DB and DataStore, reopen over the files ---
        closeSession()
        openSession()

        // Program: every field of every slot, not just the id ordering.
        val program = repository.programFlow.first()
        assertEquals(listOf("A", "B"), program.days.map { it.id })
        val reopenedA = program.days.first()
        assertEquals("Day A", reopenedA.title)
        assertEquals("Squat-focused lower", reopenedA.emphasisLine)
        assertEquals(CardioSuggestion("Intervals", "5 min easy, then 4x2min hard/easy", hard = true), reopenedA.cardio)
        assertEquals(listOf(squat, row, bench.copy(exerciseId = "db_bench")), reopenedA.exercises)
        assertEquals(dayB, program.days.last())

        // Live logs: weights, reps, kinds, done flags AND checkDate stamps.
        assertEquals(logsBeforeKill, repository.logFlow("A").first().sortedBy { it.programExerciseId })
        assertEquals(listOf(LoggedSet(255.0, 5, SetKind.TOP)), logsBeforeKill[0].sets)
        assertEquals(listOf(LoggedSet(100.0, 10, SetKind.WORK, done = true)), logsBeforeKill[1].sets)

        // Workout history: the session row and its denormalized set rows.
        val session = repository.sessionsFlow.first().single()
        assertEquals("A", session.dayId)
        assertEquals("Day A", session.dayTitle)
        assertEquals(210, session.bodyweightLb) // from the wizard answers' config
        assertTrue(session.completedAt > 0)
        val sessionSets = db.sessionDao().setsForSession(session.id)
        assertEquals(
            listOf(
                listOf("bb_back_squat", 245.0, 5, "TOP", true),
                listOf("bb_row", 95.0, 10, "WORK", true),
            ),
            sessionSets.sortedBy { it.exerciseId }
                .map { listOf(it.exerciseId, it.weightLb, it.reps, it.kind, it.done) },
        )
        assertTrue(sessionSets.all { it.exerciseName.isNotBlank() })

        // Settings: rotation pointer, wizard state, unit, custom exercise.
        assertEquals("B", repository.suggestedDayFlow.first()) // advanced past A
        assertEquals(wizardAnswers, repository.wizardAnswersFlow.first())
        assertTrue(repository.wizardCompleteFlow.first())
        assertEquals(WeightUnit.KG, repository.unitFlow.first())
        val customEntry = repository.catalogFlow.first().get(customId)
        assertEquals("Cable Hack Squat", customEntry.name)
        assertEquals(GoalSource.Flat(80.0), customEntry.goal)
    }
}
