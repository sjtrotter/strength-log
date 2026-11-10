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
import cloud.trotter.log.strength.data.db.MIGRATION_27_28
import cloud.trotter.log.strength.data.db.StrengthDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration24To25Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-24-to-25-test.db"
    private val plannedJson = "  [ ]  "

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun `fresh v24 rows keep every value and gain null or default columns after two opens`() = runTest {
        val helper = V24Fixture.create(context, name) { V24Fixture.populate(it) }
        val before = V24Fixture.dump(helper.writableDatabase)
        helper.close()
        var written: Map<String, List<List<String?>>>? = null
        repeat(2) { reopen ->
            val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
                .addMigrations(MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28).allowMainThreadQueries().build()
            try {
                val sql = db.openHelper.writableDatabase
                if (reopen == 0) {
                    V24Fixture.assertAppendedDefaults(before, V24Fixture.dump(sql))
                    sql.execSQL("UPDATE program_exercise SET ruleJson = 'rule'")
                    sql.execSQL("UPDATE program_day SET cardioRuleJson = 'cardio-rule'")
                    sql.execSQL("UPDATE session_set SET rir = 1")
                    sql.execSQL("UPDATE cardio_session SET effort = 'GOOD'")
                    sql.execSQL("UPDATE cardio_set_result SET metresAtEnd = 3470, secondsAtEnd = 900, centis = 452, timingMethod = 'PHONE', startProtocol = 'THREE_POINT'")
                    sql.execSQL("UPDATE plan SET settingsJson = 'settings', autoApply = 1, lightCycleRemaining = 4, deloadEveryCycles = 6, cyclesCompleted = 12")
                    sql.execSQL("UPDATE workout_session SET roughDay = 1, progressionUndoJson = 'undo'")
                    written = V24Fixture.dump(sql)
                } else {
                    assertEquals(written, V24Fixture.dump(sql))
                }
            } finally { db.close() }
        }
    }

    @Test fun `the 23 to 25 chain keeps the v23 fixture`() = runTest {
        val helper = V24Fixture.create(context, name, 23, V24Fixture.V23) { db ->
            db.execSQL("INSERT INTO plan VALUES (1,'Plan','AUTHORED',0,1000,0,NULL,'A',1,0,1,NULL)")
            db.execSQL("INSERT INTO program_day VALUES (1,'A',0,'Day A','',NULL,'STRENGTH',NULL,'open-run',NULL,NULL,0,NULL,NULL)")
            db.execSQL("INSERT INTO program_exercise VALUES (7,1,'A',0,'bb_back_squat','RAMP_TOP_BACKOFF',6,NULL,'Belt on top',NULL,1,NULL)")
            db.execSQL("INSERT INTO workout_run VALUES ('open-run',1,'A','Day A','Plan',1000)")
            db.execSQL("INSERT INTO run_track VALUES (11,'open-run',0,7,'main','hack_squat',0,'[]','[]',3000,0)")
            db.execSQL("INSERT INTO workout_session VALUES (9,'A','Day A',100,500,210.5,1,'Plan','finished',?,'Session note')",
                arrayOf(plannedJson))
        }
        val before = V24Fixture.dump(helper.writableDatabase)
        helper.close()
        val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
            .addMigrations(MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28).allowMainThreadQueries().build()
        try {
            val after = V24Fixture.dump(db.openHelper.writableDatabase)
            before.forEach { (table, rows) ->
                val count = V24Fixture.defaults[table].orEmpty().size +
                    if (table == "program_exercise" || table == "run_track") 1 else 0
                assertEquals(rows, after.getValue(table).map { it.dropLast(count) }, table)
            }
            val slot = db.programDao().allExercises().single()
            assertNull(slot.noteUpdatedAt)
            assertNull(slot.ruleJson)
            assertEquals("", db.runDao().allTracks().single().note)
            assertEquals(plannedJson, db.sessionDao().allSessions().single().plannedJson)
            assertEquals("Session note", db.sessionDao().allSessions().single().note)
            V24Fixture.defaults.forEach { (table, defaults) ->
                assertEquals(after.getValue(table).map { defaults }, after.getValue(table).map { it.takeLast(defaults.size) }, table)
            }
            db.programDao().setExerciseNote(7, slot.note, 4000)
            db.runDao().updateTrack(db.runDao().allTracks().single().copy(note = "Seat 4"))
            assertEquals(4000L, db.programDao().exercise(7)!!.noteUpdatedAt)
            assertEquals("Seat 4", db.runDao().allTracks().single().note)
        } finally { db.close() }
    }
}

internal object V24Fixture {
    val defaults = mapOf(
        "program_exercise" to listOf<String?>(null, null), "program_day" to listOf(null), "run_track" to listOf(null),
        "workout_run" to listOf("0"), "session_set" to listOf(null), "cardio_session" to listOf(null),
        "cardio_set_result" to List<String?>(5) { null },
        "plan" to listOf(null, "0", "0", null, "0"), "workout_session" to listOf("0", null),
    )
    val tables = listOf("plan", "program_day", "program_exercise", "exercise_log", "workout_run", "run_track",
        "workout_session", "session_set", "cardio_session", "cardio_set_result", "custom_exercise", "restore_marker")

    fun rows(db: SupportSQLiteDatabase, table: String): List<List<String?>> =
        db.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { index ->
                    if (cursor.isNull(index)) null
                    else if (cursor.getColumnName(index) == "bodyweightLb") cursor.getDouble(index).toString()
                    else cursor.getString(index)
                })
            }
        }

    fun dump(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> = tables.associateWith { rows(db, it) }

    fun assertAppendedDefaults(before: Map<String, List<List<String?>>>, after: Map<String, List<List<String?>>>) {
        before.forEach { (table, rows) ->
            val appended = defaults[table].orEmpty()
            assertEquals(rows, after.getValue(table).map { it.dropLast(appended.size) }, table)
            assertEquals(rows.map { appended }, after.getValue(table).map { it.takeLast(appended.size) }, table)
        }
    }

    fun create(context: Context, name: String, version: Int = 24, ddl: List<String> = V24,
        populate: (SupportSQLiteDatabase) -> Unit): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                ddl.forEach(db::execSQL)
                populate(db)
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).also { it.writableDatabase }
    }

    fun populate(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO plan VALUES (1,'Plan','GENERATED',0,1000,0,'recipe','A',1,1,0,NULL)")
        db.execSQL("INSERT INTO program_day VALUES (1,'A',0,'Day A','',NULL,'STRENGTH',NULL,'open-run',NULL,NULL,0,NULL,NULL)")
        db.execSQL("INSERT INTO program_day VALUES (1,'C',1,'Day C','',NULL,'CARDIO','block','cardio-run',NULL,NULL,0,NULL,NULL)")
        db.execSQL("INSERT INTO program_exercise VALUES (7,1,'A',0,'bb_back_squat','RAMP_TOP_BACKOFF',6,NULL,'Belt on top',NULL,0,NULL,NULL)")
        db.execSQL("INSERT INTO program_exercise VALUES (9,1,'A',2,'hammer_curl','STRAIGHT',3,NULL,'Curl',NULL,1,'[parked]',4000)")
        db.execSQL("INSERT INTO exercise_log VALUES (1,'A',7,'main','[]',3000)")
        db.execSQL("INSERT INTO workout_run VALUES ('open-run',1,'A','Day A','Plan',1000)")
        db.execSQL("INSERT INTO run_track VALUES (11,'open-run',0,7,'main','bb_back_squat',0,'[]','[]',3000,0,'Track note')")
        db.execSQL("INSERT INTO workout_session VALUES (9,'A','Day A',100,500,210.5,1,'Plan','finished',?,'Session note')", arrayOf("  [ ]  "))
        db.execSQL("INSERT INTO session_set VALUES (10,9,'bb_back_squat','Squat','main',0,'TOP',235.0,5,1,0,100,200,0,0)")
        db.execSQL("INSERT INTO cardio_session VALUES (12,'C','OUTDOOR_RUN',0,'Easy',1000,901000,900,1,1,'Plan','ROAD_RUN','Cardio note')")
        db.execSQL("INSERT INTO cardio_set_result VALUES (13,12,0,'spec','FINISHED',900,3470,'MANUAL')")
        db.execSQL("INSERT INTO custom_exercise VALUES ('custom_hinge','Hinge','HINGE','BARBELL',0,100.0,'WEIGHTED',NULL,NULL)")
    }

    val V24 = listOf(
        "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, `cardioBlockJson` TEXT, `nextRunId` TEXT NOT NULL, `closedAt` INTEGER, `cardioWorkingJson` TEXT, `cardioKeepToPlan` INTEGER NOT NULL DEFAULT 0, `cardioProgressBy` TEXT, `cardioIncreasePercent` INTEGER, PRIMARY KEY(`planId`, `dayId`))",
        "CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)",
        "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `structure` TEXT NOT NULL, `targetSets` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL, `prescriptionJson` TEXT, `keepToPlan` INTEGER NOT NULL, `parkedLogsJson` TEXT, `noteUpdatedAt` INTEGER DEFAULT NULL)",
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
        "CREATE TABLE IF NOT EXISTS `run_track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` TEXT NOT NULL, `position` INTEGER NOT NULL, `programExerciseId` INTEGER, `slot` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `retired` INTEGER NOT NULL, `plannedJson` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `dropped` INTEGER NOT NULL DEFAULT 0, `note` TEXT NOT NULL DEFAULT '')",
        "CREATE INDEX IF NOT EXISTS `index_run_track_runId` ON `run_track` (`runId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_run_track_runId_programExerciseId_slot_exerciseId` ON `run_track` (`runId`, `programExerciseId`, `slot`, `exerciseId`)",
    )
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
