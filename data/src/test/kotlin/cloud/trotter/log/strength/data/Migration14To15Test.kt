package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_14_15
import cloud.trotter.log.strength.data.db.MIGRATION_15_16
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration14To15Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-14-to-15-test.db"
    private val prescription = PrescriptionJson.encode(Prescription(PrescribedTarget.Reps(12)))
    private val working = SetJson.encodeSets(listOf(LoggedSet(95.0, 10, SetKind.WORK)))
    private val actual = SetJson.encodeSets(listOf(LoggedSet(100.0, 8, SetKind.WORK, done = true)))

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun `migration derives next time choices defaults notes and preserves working values and runs`() = runTest {
        createV14().also { it.writableDatabase.close(); it.close() }
        repeat(2) {
            val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
                .addMigrations(MIGRATION_14_15, MIGRATION_15_16).allowMainThreadQueries().build()
            try {
                assertEquals(listOf(
                    ProgramExerciseEntity(1, 1, "A", 0, "bb_back_squat", true, 3, "5", false, "ez_curl", "slot note",
                        prescriptionJson = prescription, keepToPlan = true),
                    ProgramExerciseEntity(2, 1, "A", 1, "bb_bench", false, 3, "8", false, null, ""),
                ), db.programDao().allExercises())
                assertEquals(listOf(
                    ExerciseLogEntity(1, "A", 1, "main", working, 1_000),
                    ExerciseLogEntity(1, "A", 1, "ss", working, 2_000),
                    ExerciseLogEntity(1, "A", 2, "main", working, 3_000),
                ), db.programDao().allLogs())
                assertEquals(listOf(WorkoutSessionEntity(7, "A", "Day A", 1_000, 2_000, 235, 1, "My plan")),
                    db.sessionDao().allSessions())
                assertEquals("", db.sessionDao().allSessions().single().note)
                assertEquals(listOf(WorkoutRunEntity("run-A", 1, "A", "Day A", "My plan", 4_000)), db.runDao().allRuns())
                assertEquals(listOf(RunTrackEntity(9, "run-A", 0, 1, "main", "bb_back_squat", false, working, actual, 5_000)),
                    db.runDao().allTracks())
            } finally { db.close() }
        }
    }

    private fun createV14(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(14) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V14.forEach(db::execSQL)
                db.execSQL("INSERT INTO plan VALUES (1,'My plan','GENERATED',0,1000,0,NULL,'A')")
                db.execSQL("INSERT INTO program_day VALUES (1,'A',0,'Day A','',NULL,'STRENGTH',NULL,'run-A',NULL)")
                db.execSQL("INSERT INTO program_exercise VALUES (1,1,'A',0,'bb_back_squat',1,3,'5',0,'ez_curl','slot note',?)",
                    arrayOf(prescription))
                db.execSQL("INSERT INTO program_exercise VALUES (2,1,'A',1,'bb_bench',0,3,'8',0,NULL,'',NULL)")
                db.execSQL("INSERT INTO exercise_log VALUES (1,'A',1,'main',?,1000)", arrayOf(working))
                db.execSQL("INSERT INTO exercise_log VALUES (1,'A',1,'ss',?,2000)", arrayOf(working))
                db.execSQL("INSERT INTO exercise_log VALUES (1,'A',2,'main',?,3000)", arrayOf(working))
                db.execSQL("INSERT INTO workout_session VALUES (7,'A','Day A',1000,2000,235,1,'My plan',NULL,NULL)")
                db.execSQL("INSERT INTO workout_run VALUES ('run-A',1,'A','Day A','My plan',4000)")
                db.execSQL("INSERT INTO run_track VALUES (9,'run-A',0,1,'main','bb_back_squat',0,?,?,5000)", arrayOf(working, actual))
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        )
    }

    private companion object {
        val V14 = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, `cardioBlockJson` TEXT, `nextRunId` TEXT NOT NULL, `closedAt` INTEGER, PRIMARY KEY(`planId`, `dayId`))",
            "CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL, `prescriptionJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_planId_dayId` ON `program_exercise` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`planId`, `dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_planId_dayId` ON `exercise_log` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` INTEGER, `planId` INTEGER, `planName` TEXT, `runId` TEXT, `plannedJson` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_session_runId` ON `workout_session` (`runId`)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER, `trackPosition` INTEGER, `plannedIndex` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `restore_marker` (`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL, `planId` INTEGER, `planName` TEXT, `activity` TEXT)",
            "CREATE TABLE IF NOT EXISTS `cardio_set_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardioSessionId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `specJson` TEXT NOT NULL, `status` TEXT NOT NULL, `elapsedSeconds` INTEGER NOT NULL, `metres` INTEGER, `distanceSource` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_cardio_set_result_cardioSessionId` ON `cardio_set_result` (`cardioSessionId`)",
            "CREATE TABLE IF NOT EXISTS `plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `recipeJson` TEXT, `suggestedDayId` TEXT)",
            "CREATE TABLE IF NOT EXISTS `workout_run` (`id` TEXT NOT NULL, `planId` INTEGER, `dayId` TEXT, `dayTitle` TEXT NOT NULL, `planName` TEXT, `startedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `run_track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` TEXT NOT NULL, `position` INTEGER NOT NULL, `programExerciseId` INTEGER, `slot` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `retired` INTEGER NOT NULL, `plannedJson` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_run_track_runId` ON `run_track` (`runId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_run_track_runId_programExerciseId_slot_exerciseId` ON `run_track` (`runId`, `programExerciseId`, `slot`, `exerciseId`)",
        )
    }
}
