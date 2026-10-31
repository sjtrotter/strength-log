package cloud.trotter.log.strength.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_12_13
import cloud.trotter.log.strength.data.db.MIGRATION_13_14
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Additive v13 storage preserves legacy rows and gives each day a distinct identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration12To13Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-12-to-13-test.db"
    private val plan = PlanEntity(1, "My plan", "GENERATED", 0, 1000, suggestedDayId = "A")
    private val slot = ProgramExerciseEntity(1, 1, "A", 0, "bb_back_squat", true, 3, "5", true, null, "old note")
    private val log = ExerciseLogEntity(1, "A", 1, "main", "[]", 1000)
    private val session = WorkoutSessionEntity(7, "A", "Day A", 1000, 2000, 235, 1, "My plan")
    private val set = SessionSetEntity(9, 7, "bb_back_squat", "Squat", "main", 0, "TOP", 235.0, 5, true)

    @After fun cleanup() { context.deleteDatabase(name) }

    /** [F] */
    @Test
    fun `additive migration mints distinct day ids preserves rows and persists after reopen`() = runTest {
        createV12().also { it.writableDatabase.close(); it.close() }
        val migratedDays: List<ProgramDayEntity>
        val stamped = session.copy(id = 8, runId = "finished-run")
        val db = open()
        try {
            migratedDays = db.programDao().allDays()
            assertEquals(2, migratedDays.size)
            assertEquals(2, migratedDays.map { UUID.fromString(it.nextRunId) }.toSet().size)
            migratedDays.forEach { day ->
                assertNull(day.closedAt)
                assertEquals(ProgramDayEntity(1, day.dayId, day.position, "Day ${day.dayId}", "", null),
                    day.copy(nextRunId = ""))
            }
            assertEquals(listOf(plan), db.planDao().allPlans())
            assertEquals(listOf(slot), db.programDao().allExercises())
            assertEquals(listOf(log), db.programDao().allLogs())
            assertEquals(listOf(session), db.sessionDao().allSessions())
            assertEquals(listOf(set), db.sessionDao().allSessionSets())
            assertNull(db.sessionDao().allSessions().single().plannedJson)
            assertTrue(db.runDao().allRuns().isEmpty())
            assertTrue(db.runDao().allTracks().isEmpty())
            db.sessionDao().insertSession(stamped)
            val failure = runCatching { db.sessionDao().insertSession(stamped.copy(id = 10)) }.exceptionOrNull()
            assertTrue(failure is SQLiteConstraintException)
        } finally { db.close() }
        val reopened = open()
        try {
            assertEquals(migratedDays, reopened.programDao().allDays())
            assertEquals(listOf(session, stamped), reopened.sessionDao().allSessions())
            assertEquals(listOf(set), reopened.sessionDao().allSessionSets())
            assertEquals(listOf(log), reopened.programDao().allLogs())
            assertTrue(reopened.runDao().allRuns().isEmpty())
        } finally { reopened.close() }
    }

    private fun open(): StrengthDatabase = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
        .addMigrations(MIGRATION_12_13).addMigrations(MIGRATION_13_14).allowMainThreadQueries().build()

    private fun createV12(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(12) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V12.forEach(db::execSQL)
                db.execSQL("INSERT INTO plan VALUES (1,'My plan','GENERATED',0,1000,0,NULL,'A')")
                db.execSQL("INSERT INTO program_day VALUES (1,'A',0,'Day A','',NULL,'STRENGTH',NULL)")
                db.execSQL("INSERT INTO program_day VALUES (1,'B',1,'Day B','',NULL,'STRENGTH',NULL)")
                db.execSQL("INSERT INTO program_exercise VALUES (1,1,'A',0,'bb_back_squat',1,3,'5',1,NULL,'old note',NULL)")
                db.execSQL("INSERT INTO exercise_log VALUES (1,'A',1,'main','[]','2026-10-19',1000)")
                db.execSQL("INSERT INTO workout_session VALUES (7,'A','Day A',1000,2000,235,1,'My plan')")
                db.execSQL("INSERT INTO session_set VALUES (9,7,'bb_back_squat','Squat','main',0,'TOP',235.0,5,1,0,NULL,NULL)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).also { it.writableDatabase }
    }

    private companion object {
        val V12 = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, `cardioBlockJson` TEXT, PRIMARY KEY(`planId`, `dayId`))",
            "CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL, `prescriptionJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_planId_dayId` ON `program_exercise` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `checkDate` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`planId`, `dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_planId_dayId` ON `exercise_log` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` INTEGER, `planId` INTEGER, `planName` TEXT)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `restore_marker` (`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL, `planId` INTEGER, `planName` TEXT, `activity` TEXT)",
            "CREATE TABLE IF NOT EXISTS `cardio_set_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardioSessionId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `specJson` TEXT NOT NULL, `status` TEXT NOT NULL, `elapsedSeconds` INTEGER NOT NULL, `metres` INTEGER, `distanceSource` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_cardio_set_result_cardioSessionId` ON `cardio_set_result` (`cardioSessionId`)",
            "CREATE TABLE IF NOT EXISTS `plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `recipeJson` TEXT, `suggestedDayId` TEXT)",
        )
    }
}
