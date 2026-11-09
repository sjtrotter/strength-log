package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_23_24
import cloud.trotter.log.strength.data.db.MIGRATION_24_25
import cloud.trotter.log.strength.data.db.MIGRATION_25_26
import cloud.trotter.log.strength.data.db.MIGRATION_26_27
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
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
class Migration23To24Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-23-to-24-test.db"
    private val plannedJson = "  [ ]  "

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun `v23 rows get null note timestamps and empty track notes and preserve history after two opens`() = runTest {
        createV23().close()
        repeat(2) { reopen ->
            val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
                .addMigrations(MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27).allowMainThreadQueries().build()
            try {
                val slot = db.programDao().allExercises().single()
                val track = db.runDao().allTracks().single()
                assertEquals(ProgramExerciseEntity(7, 1, "A", 0, "bb_back_squat", "RAMP_TOP_BACKOFF", 6,
                    null, "Belt on top", keepToPlan = true,
                    noteUpdatedAt = if (reopen == 0) null else 4000L), slot)
                assertEquals(RunTrackEntity(11, "open-run", 0, 7, "main", "hack_squat", false,
                    "[]", "[]", 3000, note = if (reopen == 0) "" else "Seat 4"), track)
                assertEquals(WorkoutRunEntity("open-run", 1, "A", "Day A", "Plan", 1000),
                    db.runDao().allRuns().single())
                val session = db.sessionDao().allSessions().single()
                assertEquals(plannedJson, session.plannedJson)
                assertEquals("Session note", session.note)
                if (reopen == 0) {
                    assertNull(slot.noteUpdatedAt)
                    db.openHelper.writableDatabase.query("SELECT noteUpdatedAt FROM program_exercise").use { cursor ->
                        check(cursor.moveToFirst())
                        assertNull(cursor.getString(0))
                    }
                    db.openHelper.writableDatabase.query("SELECT note FROM run_track").use { cursor ->
                        check(cursor.moveToFirst())
                        assertEquals("", cursor.getString(0))
                    }
                    db.programDao().setExerciseNote(slot.id, slot.note, 4000)
                    db.runDao().updateTrack(track.copy(note = "Seat 4"))
                }
            } finally { db.close() }
        }
    }

    private fun createV23(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(23) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V23.forEach(db::execSQL)
                db.execSQL("INSERT INTO plan VALUES (1,'Plan','AUTHORED',0,1000,0,NULL,'A',1,0,1,NULL)")
                db.execSQL("INSERT INTO program_day VALUES (1,'A',0,'Day A','',NULL,'STRENGTH',NULL,'open-run',NULL,NULL,0,NULL,NULL)")
                db.execSQL("INSERT INTO program_exercise VALUES (7,1,'A',0,'bb_back_squat','RAMP_TOP_BACKOFF',6,NULL,'Belt on top',NULL,1,NULL)")
                db.execSQL("INSERT INTO workout_run VALUES ('open-run',1,'A','Day A','Plan',1000)")
                db.execSQL("INSERT INTO run_track VALUES (11,'open-run',0,7,'main','hack_squat',0,'[]','[]',3000,0)")
                db.execSQL("INSERT INTO workout_session VALUES (9,'A','Day A',100,500,210.5,1,'Plan','finished',?,'Session note')",
                    arrayOf(plannedJson))
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).also { it.writableDatabase }
    }

    private companion object {
        val V23 = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, `cardioBlockJson` TEXT, `nextRunId` TEXT NOT NULL, `closedAt` INTEGER, `cardioWorkingJson` TEXT, `cardioKeepToPlan` INTEGER NOT NULL DEFAULT 0, `cardioProgressBy` TEXT, `cardioIncreasePercent` INTEGER, PRIMARY KEY(`planId`, `dayId`))",
            "CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `structure` TEXT NOT NULL, `targetSets` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL, `prescriptionJson` TEXT, `keepToPlan` INTEGER NOT NULL, `parkedLogsJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_planId_dayId` ON `program_exercise` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`planId`, `dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_planId_dayId` ON `exercise_log` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` REAL, `planId` INTEGER, `planName` TEXT, `runId` TEXT, `plannedJson` TEXT, `note` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_session_runId` ON `workout_session` (`runId`)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER, `trackPosition` INTEGER, `plannedIndex` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `restore_marker` (`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL, `planId` INTEGER, `planName` TEXT, `activity` TEXT, `note` TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS `cardio_set_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardioSessionId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `specJson` TEXT NOT NULL, `status` TEXT NOT NULL, `elapsedSeconds` INTEGER NOT NULL, `metres` INTEGER, `distanceSource` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_cardio_set_result_cardioSessionId` ON `cardio_set_result` (`cardioSessionId`)",
            "CREATE TABLE IF NOT EXISTS `plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `recipeJson` TEXT, `suggestedDayId` TEXT, `onToday` INTEGER NOT NULL DEFAULT 0, `offerStrengthIncreases` INTEGER NOT NULL DEFAULT 1, `offerCardioIncreases` INTEGER NOT NULL DEFAULT 0, `goalJson` TEXT)",
            "CREATE TABLE IF NOT EXISTS `workout_run` (`id` TEXT NOT NULL, `planId` INTEGER, `dayId` TEXT, `dayTitle` TEXT NOT NULL, `planName` TEXT, `startedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `run_track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` TEXT NOT NULL, `position` INTEGER NOT NULL, `programExerciseId` INTEGER, `slot` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `retired` INTEGER NOT NULL, `plannedJson` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `dropped` INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS `index_run_track_runId` ON `run_track` (`runId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_run_track_runId_programExerciseId_slot_exerciseId` ON `run_track` (`runId`, `programExerciseId`, `slot`, `exerciseId`)",
        )
    }
}
