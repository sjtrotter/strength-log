package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_18_19
import cloud.trotter.log.strength.data.db.MIGRATION_19_20
import cloud.trotter.log.strength.data.db.MIGRATION_20_21
import cloud.trotter.log.strength.data.db.MIGRATION_21_22
import cloud.trotter.log.strength.data.db.MIGRATION_22_23
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration18To19Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-18-to-19-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun wholePoundsAndUnknownWeightsSurviveAndTenthsPersistAfterReopen() = runTest {
        createV18().close()
        repeat(2) { reopen ->
            val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
                .addMigrations(MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23).allowMainThreadQueries().build()
            try {
                val sessions = db.sessionDao().allSessions().associateBy { it.id }
                assertEquals(235.0, sessions.getValue(7).bodyweightLb ?: Double.NaN, 0.0)
                assertNull(sessions.getValue(9).bodyweightLb)
                val recorded = sessions.getValue(7)
                assertEquals("finished", recorded.runId)
                assertEquals("Plan", recorded.planName)
                assertEquals("session note", recorded.note)
                assertEquals("[]", recorded.plannedJson)
                if (reopen == 0) {
                    assertEquals(901L, db.sessionDao().insertSession(
                        WorkoutSessionEntity(0, "A", "Day A", null, 3000, 210.5),
                    ))
                } else {
                    assertEquals(210.5, sessions.getValue(901).bodyweightLb ?: Double.NaN, 0.0)
                }
            } finally { db.close() }
        }
    }

    @Test fun emptySessionTableKeepsItsIssuedSequence() = runTest {
        createV18(empty = true).close()
        val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
            .addMigrations(MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23).allowMainThreadQueries().build()
        try {
            assertEquals(901L, db.sessionDao().insertSession(
                WorkoutSessionEntity(0, "A", "Day A", null, 3000, 210.5),
            ))
        } finally { db.close() }
    }

    private fun createV18(empty: Boolean = false): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(18) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V18.forEach(db::execSQL)
                db.execSQL("INSERT INTO workout_session VALUES (7,'A','Day A',1000,2000,235,1,'Plan','finished','[]','session note')")
                db.execSQL("INSERT INTO workout_session VALUES (9,'csv:A','Imported',NULL,2001,NULL,NULL,NULL,NULL,NULL,'')")
                db.execSQL("INSERT INTO workout_session VALUES (900,'A','Deleted',NULL,2002,200,NULL,NULL,NULL,NULL,'')")
                db.execSQL(if (empty) "DELETE FROM workout_session" else "DELETE FROM workout_session WHERE id = 900")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).also { it.writableDatabase }
    }

    private companion object {
        val V18 = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, `cardioBlockJson` TEXT, `nextRunId` TEXT NOT NULL, `closedAt` INTEGER, PRIMARY KEY(`planId`, `dayId`))",
            "CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `structure` TEXT NOT NULL, `targetSets` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL, `prescriptionJson` TEXT, `keepToPlan` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_planId_dayId` ON `program_exercise` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`planId`, `dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_planId_dayId` ON `exercise_log` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` INTEGER, `planId` INTEGER, `planName` TEXT, `runId` TEXT, `plannedJson` TEXT, `note` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_session_runId` ON `workout_session` (`runId`)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER, `trackPosition` INTEGER, `plannedIndex` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `restore_marker` (`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL, `planId` INTEGER, `planName` TEXT, `activity` TEXT)",
            "CREATE TABLE IF NOT EXISTS `cardio_set_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardioSessionId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `specJson` TEXT NOT NULL, `status` TEXT NOT NULL, `elapsedSeconds` INTEGER NOT NULL, `metres` INTEGER, `distanceSource` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_cardio_set_result_cardioSessionId` ON `cardio_set_result` (`cardioSessionId`)",
            "CREATE TABLE IF NOT EXISTS `plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `recipeJson` TEXT, `suggestedDayId` TEXT, `onToday` INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS `workout_run` (`id` TEXT NOT NULL, `planId` INTEGER, `dayId` TEXT, `dayTitle` TEXT NOT NULL, `planName` TEXT, `startedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `run_track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` TEXT NOT NULL, `position` INTEGER NOT NULL, `programExerciseId` INTEGER, `slot` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `retired` INTEGER NOT NULL, `plannedJson` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `dropped` INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS `index_run_track_runId` ON `run_track` (`runId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_run_track_runId_programExerciseId_slot_exerciseId` ON `run_track` (`runId`, `programExerciseId`, `slot`, `exerciseId`)",
        )
    }
}
