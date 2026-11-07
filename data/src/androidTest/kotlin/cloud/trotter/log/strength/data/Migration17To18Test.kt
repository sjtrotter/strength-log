package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.trotter.log.strength.data.db.MIGRATION_17_18
import cloud.trotter.log.strength.data.db.MIGRATION_18_19
import cloud.trotter.log.strength.data.db.MIGRATION_19_20
import cloud.trotter.log.strength.data.db.MIGRATION_20_21
import cloud.trotter.log.strength.data.db.MIGRATION_21_22
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration17To18Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-17-to-18-test.db"
    private val prescription = PrescriptionJson.encode(Prescription(PrescribedTarget.LoadReps(225.0, 5)))
    private val retainedColumns = "id, planId, dayId, position, exerciseId, targetSets, " +
        "supersetExerciseId, note, prescriptionJson, keepToPlan"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun legacySlotsKeepEveryOtherColumnAndSequence() = runTest {
        migrateAndCheck(empty = false)
    }

    @Test fun emptySlotTableKeepsItsSequence() = runTest {
        migrateAndCheck(empty = true)
    }

    @Test fun unusedSlotTableKeepsItsAbsentSequence() = runTest {
        migrateAndCheck(empty = true, issued = false)
    }

    private suspend fun migrateAndCheck(empty: Boolean, issued: Boolean = true) {
        val helper = createV17(empty, issued)
        val old = helper.writableDatabase
        val before = rows(old, "SELECT $retainedColumns FROM program_exercise ORDER BY id")
        val sequences = rows(old, "SELECT name, seq FROM sqlite_sequence ORDER BY name")
        helper.close()
        repeat(2) { reopen ->
            val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
                .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22).allowMainThreadQueries().build()
            try {
                val sql = db.openHelper.writableDatabase
                assertEquals(before, rows(sql, "SELECT $retainedColumns FROM program_exercise ORDER BY id"))
                assertEquals(sequences, rows(sql, "SELECT name, seq FROM sqlite_sequence ORDER BY name"))
                val slots = db.programDao().allExercises().sortedBy { it.id }
                assertEquals(
                    if (empty) emptyList<String>() else listOf("RAMP_TOP_BACKOFF", "STRAIGHT", "RAMP_TOP_BACKOFF", "STRAIGHT"),
                    slots.map { it.structure },
                )
                val columns = sql.query("PRAGMA table_info(program_exercise)").use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) {
                            val column = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                            add(column)
                            if (column == "structure") {
                                assertEquals("TEXT", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                            }
                        }
                    }
                }
                assertTrue("structure" in columns)
                assertFalse("isMain" in columns)
                assertFalse("repSchemeLabel" in columns)
                assertFalse("hasWarmupHint" in columns)
                if (reopen == 1) {
                    assertEquals(if (issued) 901L else 1L, db.programDao().insertExercise(
                        ProgramExerciseEntity(0, 1, "A", 4, "bb_bench", "RAMP_TOP", 3, null, ""),
                    ))
                }
            } finally { db.close() }
        }
    }

    private fun createV17(empty: Boolean, issued: Boolean): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(17) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V17.forEach(db::execSQL)
                db.execSQL("INSERT INTO plan VALUES (1,'Plan','GENERATED',0,1000,0,NULL,'A',1)")
                db.execSQL("INSERT INTO program_day VALUES (1,'A',0,'Day A','',NULL,'STRENGTH',NULL,'next-run',NULL)")
                if (!issued) return
                db.execSQL("INSERT INTO program_exercise VALUES (7,1,'A',2,'bb_back_squat',1,6,'ramp',1,'ez_curl','main note',NULL,0)")
                db.execSQL("INSERT INTO program_exercise VALUES (9,1,'A',0,'bb_bench',0,4,'5',0,NULL,'straight note',?,1)", arrayOf(prescription))
                db.execSQL("INSERT INTO program_exercise VALUES (11,1,'A',1,'bb_bench',1,2,'5',0,NULL,'prescribed main',?,0)", arrayOf(prescription))
                db.execSQL("INSERT INTO program_exercise VALUES (13,1,'A',3,'ez_curl',0,3,'10',1,NULL,'',NULL,1)")
                db.execSQL("INSERT INTO program_exercise SELECT 900,planId,dayId,position,exerciseId,isMain,targetSets,repSchemeLabel,hasWarmupHint,supersetExerciseId,note,prescriptionJson,keepToPlan FROM program_exercise WHERE id = 7")
                db.execSQL(if (empty) "DELETE FROM program_exercise" else "DELETE FROM program_exercise WHERE id = 900")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        )
    }

    private fun rows(db: SupportSQLiteDatabase, query: String): List<List<String?>> =
        db.query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                })
            }
        }

    private companion object {
        val V17 = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, `cardioBlockJson` TEXT, `nextRunId` TEXT NOT NULL, `closedAt` INTEGER, PRIMARY KEY(`planId`, `dayId`))",
            "CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL, `prescriptionJson` TEXT, `keepToPlan` INTEGER NOT NULL)",
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
