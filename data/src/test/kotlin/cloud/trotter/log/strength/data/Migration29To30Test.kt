package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_29_30
import cloud.trotter.log.strength.data.db.MIGRATION_30_31
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ReadingSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration29To30Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-29-to-30-test.db"
    private val specJson = CardioBlockJson.encodeSet(CardioSetSpec(CardioRole.WORK, Measure.Time(900)))

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun `v29 readings gain a null source and typed sources persist over two opens`() = runTest {
        val helper = createV29()
        val before = V24Fixture.dump(helper.writableDatabase)
        val sequence = V24Fixture.rows(helper.writableDatabase, "sqlite_sequence")
        helper.close()
        repeat(2) { reopen ->
            val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
                .addMigrations(MIGRATION_29_30, MIGRATION_30_31).allowMainThreadQueries().build()
            try {
                val sql = db.openHelper.writableDatabase
                assertEquals(sequence, V24Fixture.rows(sql, "sqlite_sequence"))
                if (reopen == 0) {
                    val after = V24Fixture.dump(sql)
                    before.forEach { (table, rows) ->
                        assertEquals(if (table == "cardio_set_result") rows.map { it + listOf<String?>(null) } else rows,
                            after.getValue(table), table)
                    }
                    sql.query("PRAGMA table_info(cardio_set_result)").use { cursor ->
                        assertEquals(14, cursor.count)
                        assertTrue(cursor.moveToLast())
                        assertEquals("readingSource", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                        assertEquals("TEXT", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                        assertEquals("NULL", cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                    }
                }
                val rows = db.cardioSetResultDao().forSession(12)
                val row = rows.first()
                assertEquals(13L, row.id)
                assertEquals(specJson, row.specJson)
                assertEquals("FINISHED", row.status)
                assertEquals(904, row.elapsedSeconds)
                assertEquals(3485, row.metres)
                assertEquals("MANUAL", row.distanceSource)
                assertEquals(3470 to 900, row.metresAtEnd to row.secondsAtEnd)
                assertEquals(452, row.centis)
                assertEquals("PHONE", row.timingMethod)
                assertEquals("THREE_POINT", row.startProtocol)
                assertEquals(if (reopen == 0) null else "TYPED", row.readingSource)
                assertNull(rows[1].metresAtEnd)
                assertNull(rows[1].secondsAtEnd)
                assertNull(rows[1].readingSource)
                if (reopen == 0) {
                    db.cardioSetResultDao().update(row.copy(readingSource = "TYPED"))
                } else {
                    assertEquals(ReadingSource.TYPED, row.toDomain().readingSource)
                    db.cardioSetResultDao().insertAll(listOf(row.toDomain().toEntity(12, 2)))
                    val inserted = db.cardioSetResultDao().forSession(12).last()
                    assertEquals(901L, inserted.id)
                    assertEquals("TYPED", inserted.readingSource)
                }
            } finally { db.close() }
        }
    }

    private fun createV29(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(29) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V29.forEach(db::execSQL)
                db.execSQL("INSERT INTO cardio_session VALUES (12,'A','OUTDOOR_RUN',0,'Run',1000,905000,904,1,NULL,NULL,'ROAD_RUN','Steady','GOOD',1)")
                db.execSQL("INSERT INTO cardio_set_result VALUES (13,12,0,?,'FINISHED',904,3485,'MANUAL',3470,900,452,'PHONE','THREE_POINT')", arrayOf(specJson))
                db.execSQL("INSERT INTO cardio_set_result VALUES (14,12,1,?,'SKIPPED',0,NULL,NULL,NULL,NULL,NULL,NULL,NULL)", arrayOf(specJson))
                db.execSQL("INSERT INTO cardio_set_result SELECT 900,cardioSessionId,2,specJson,status,elapsedSeconds,metres,distanceSource,metresAtEnd,secondsAtEnd,centis,timingMethod,startProtocol FROM cardio_set_result WHERE id = 13")
                db.execSQL("DELETE FROM cardio_set_result WHERE id = 900")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).also { it.writableDatabase }
    }

    private companion object {
        val V29 = listOf(
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
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL, `planId` INTEGER, `planName` TEXT, `activity` TEXT, `note` TEXT NOT NULL DEFAULT '', `effort` TEXT, `increaseApplied` INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS `cardio_set_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardioSessionId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `specJson` TEXT NOT NULL, `status` TEXT NOT NULL, `elapsedSeconds` INTEGER NOT NULL, `metres` INTEGER, `distanceSource` TEXT, `metresAtEnd` INTEGER, `secondsAtEnd` INTEGER, `centis` INTEGER, `timingMethod` TEXT, `startProtocol` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_cardio_set_result_cardioSessionId` ON `cardio_set_result` (`cardioSessionId`)",
            "CREATE TABLE IF NOT EXISTS `plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `recipeJson` TEXT, `suggestedDayId` TEXT, `onToday` INTEGER NOT NULL DEFAULT 0, `offerStrengthIncreases` INTEGER NOT NULL DEFAULT 1, `offerCardioIncreases` INTEGER NOT NULL DEFAULT 0, `goalJson` TEXT, `settingsJson` TEXT, `autoApply` INTEGER NOT NULL DEFAULT 0, `lightCycleRemaining` INTEGER NOT NULL DEFAULT 0, `deloadEveryCycles` INTEGER, `cyclesCompleted` INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS `workout_run` (`id` TEXT NOT NULL, `planId` INTEGER, `dayId` TEXT, `dayTitle` TEXT NOT NULL, `planName` TEXT, `startedAt` INTEGER NOT NULL, `draft` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `run_track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` TEXT NOT NULL, `position` INTEGER NOT NULL, `programExerciseId` INTEGER, `slot` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `retired` INTEGER NOT NULL, `plannedJson` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `dropped` INTEGER NOT NULL DEFAULT 0, `note` TEXT NOT NULL DEFAULT '', `openingJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_run_track_runId` ON `run_track` (`runId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_run_track_runId_programExerciseId_slot_exerciseId` ON `run_track` (`runId`, `programExerciseId`, `slot`, `exerciseId`)",
        )
    }
}
