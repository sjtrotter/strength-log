package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_16_17
import cloud.trotter.log.strength.data.db.StrengthDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration16To17Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-16-to-17-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun `v16 run tracks survive with dropped defaulting to zero`() = runTest {
        val callback = object : SupportSQLiteOpenHelper.Callback(16) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                listOf(
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
                "CREATE TABLE IF NOT EXISTS `run_track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` TEXT NOT NULL, `position` INTEGER NOT NULL, `programExerciseId` INTEGER, `slot` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `retired` INTEGER NOT NULL, `plannedJson` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS `index_run_track_runId` ON `run_track` (`runId`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_run_track_runId_programExerciseId_slot_exerciseId` ON `run_track` (`runId`, `programExerciseId`, `slot`, `exerciseId`)",
                ).forEach(db::execSQL)
                db.execSQL("INSERT INTO workout_run VALUES ('open',1,'B','Day B','Preferred',4000)")
                db.execSQL("INSERT INTO run_track VALUES (11,'open',0,7,'main','bb_back_squat',0,'[]','[]',5000)")
                db.execSQL("INSERT INTO run_track VALUES (12,'open',0,7,'ss','ez_curl',1,'[]','[]',6000)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        )
        val tables = listOf("workout_run", "run_track")
        val before = tables.associateWith { rows(helper.writableDatabase, it) }
        helper.close()
        val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
            .addMigrations(MIGRATION_16_17).allowMainThreadQueries().build()
        try {
            assertTrue(db.runDao().tracks("open").none { it.dropped })
            db.openHelper.writableDatabase.query("SELECT dropped FROM run_track").use { cursor ->
                while (cursor.moveToNext()) assertEquals(0, cursor.getInt(0))
            }
            tables.forEach { table ->
                val after = rows(db.openHelper.writableDatabase, table)
                assertEquals(before.getValue(table), if (table == "run_track") after.map { it.dropLast(1) } else after)
            }
        } finally { db.close() }
    }

    private fun rows(db: SupportSQLiteDatabase, table: String): List<List<String?>> =
        db.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                })
            }
        }
}
