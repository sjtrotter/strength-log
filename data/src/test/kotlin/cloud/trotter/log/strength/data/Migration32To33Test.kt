package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_32_33
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.mapping.sides
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration32To33Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-32-to-33-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun `case 43 v32 session sets open with no sides over two opens`() = runTest {
        val helper = V24Fixture.create(context, name, 32, V32) { sql ->
            sql.execSQL("INSERT INTO workout_session VALUES (9,'A','Day A',100,200,180.0,NULL,NULL,NULL,NULL,'',0,NULL)")
            sql.execSQL("INSERT INTO session_set VALUES (10,9,'side_plank','Side Plank','main',0,'WORK',0.0,0,1,40,100,200,0,0,NULL)")
        }
        val before = V24Fixture.rows(helper.writableDatabase, "session_set")
        helper.close()
        repeat(2) {
            val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
                .addMigrations(MIGRATION_32_33).allowMainThreadQueries().build()
            try {
                val row = db.sessionDao().setsForSession(9).single()
                assertNull(row.leftWeightLb)
                assertNull(row.leftReps)
                assertNull(row.leftSeconds)
                assertNull(row.rightWeightLb)
                assertNull(row.rightReps)
                assertNull(row.rightSeconds)
                assertNull(row.sides())
                assertEquals(40, row.seconds)
                assertTrue(row.done)
                assertEquals("side_plank", row.exerciseId)
                val sql = db.openHelper.writableDatabase
                assertEquals(before.map { it + List<String?>(6) { null } }, V24Fixture.rows(sql, "session_set"))
                sql.query("PRAGMA table_info(session_set)").use { cursor ->
                    val columns = buildList {
                        while (cursor.moveToNext()) add(Triple(
                            cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                        ))
                    }
                    assertEquals(listOf(
                        Triple("leftWeightLb", "REAL", 0), Triple("leftReps", "INTEGER", 0), Triple("leftSeconds", "INTEGER", 0),
                        Triple("rightWeightLb", "REAL", 0), Triple("rightReps", "INTEGER", 0), Triple("rightSeconds", "INTEGER", 0),
                    ), columns.takeLast(6))
                }
            } finally { db.close() }
        }
    }

    private companion object {
        val V32 = listOf(
            "CREATE TABLE IF NOT EXISTS `cardio_route` (`cardioSessionId` INTEGER NOT NULL, `pointsJson` TEXT NOT NULL, PRIMARY KEY(`cardioSessionId`))",
            "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, `cardioBlockJson` TEXT, `nextRunId` TEXT NOT NULL, `closedAt` INTEGER, `cardioWorkingJson` TEXT, `cardioKeepToPlan` INTEGER NOT NULL DEFAULT 0, `cardioProgressBy` TEXT, `cardioIncreasePercent` INTEGER, `cardioRuleJson` TEXT, PRIMARY KEY(`planId`, `dayId`))",
            "CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `structure` TEXT NOT NULL, `targetSets` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL, `prescriptionJson` TEXT, `keepToPlan` INTEGER NOT NULL, `parkedLogsJson` TEXT, `noteUpdatedAt` INTEGER DEFAULT NULL, `ruleJson` TEXT, `partnerRuleJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_planId_dayId` ON `program_exercise` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`planId`, `dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_planId_dayId` ON `exercise_log` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` REAL, `planId` INTEGER, `planName` TEXT, `runId` TEXT, `plannedJson` TEXT, `note` TEXT NOT NULL, `roughDay` INTEGER NOT NULL DEFAULT 0, `progressionUndoJson` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_session_runId` ON `workout_session` (`runId`)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER, `trackPosition` INTEGER, `plannedIndex` INTEGER, `rir` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `restore_marker` (`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL, `planId` INTEGER, `planName` TEXT, `activity` TEXT, `note` TEXT NOT NULL DEFAULT '', `effort` TEXT, `increaseApplied` INTEGER NOT NULL DEFAULT 0, `progressionUndoJson` TEXT)",
            "CREATE TABLE IF NOT EXISTS `cardio_set_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardioSessionId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `specJson` TEXT NOT NULL, `status` TEXT NOT NULL, `elapsedSeconds` INTEGER NOT NULL, `metres` INTEGER, `distanceSource` TEXT, `metresAtEnd` INTEGER, `secondsAtEnd` INTEGER, `centis` INTEGER, `timingMethod` TEXT, `startProtocol` TEXT, `readingSource` TEXT DEFAULT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_cardio_set_result_cardioSessionId` ON `cardio_set_result` (`cardioSessionId`)",
            "CREATE TABLE IF NOT EXISTS `plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `recipeJson` TEXT, `suggestedDayId` TEXT, `onToday` INTEGER NOT NULL DEFAULT 0, `offerStrengthIncreases` INTEGER NOT NULL DEFAULT 1, `offerCardioIncreases` INTEGER NOT NULL DEFAULT 0, `goalJson` TEXT, `settingsJson` TEXT, `autoApply` INTEGER NOT NULL DEFAULT 0, `lightCycleRemaining` INTEGER NOT NULL DEFAULT 0, `deloadEveryCycles` INTEGER, `cyclesCompleted` INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS `workout_run` (`id` TEXT NOT NULL, `planId` INTEGER, `dayId` TEXT, `dayTitle` TEXT NOT NULL, `planName` TEXT, `startedAt` INTEGER NOT NULL, `draft` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `run_track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` TEXT NOT NULL, `position` INTEGER NOT NULL, `programExerciseId` INTEGER, `slot` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `retired` INTEGER NOT NULL, `plannedJson` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `dropped` INTEGER NOT NULL DEFAULT 0, `note` TEXT NOT NULL DEFAULT '', `openingJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_run_track_runId` ON `run_track` (`runId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_run_track_runId_programExerciseId_slot_exerciseId` ON `run_track` (`runId`, `programExerciseId`, `slot`, `exerciseId`)",
        )
    }
}
