package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_7_8
import cloud.trotter.log.strength.data.db.MIGRATION_8_9
import cloud.trotter.log.strength.data.db.MIGRATION_9_10
import cloud.trotter.log.strength.data.db.MIGRATION_10_11
import cloud.trotter.log.strength.data.db.MIGRATION_11_12
import cloud.trotter.log.strength.data.db.MIGRATION_12_13
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanEntity
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
class Migration7To8Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-7-to-8-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test
    fun `existing program becomes plan 1 without changing live or historical rows`() {
        val helper = createV7 { db ->
            db.execSQL("INSERT INTO program_day VALUES ('A',0,'Day A','Squat',NULL,'STRENGTH')")
            db.execSQL("INSERT INTO program_day VALUES ('B',1,'Day B','Bench',NULL,'STRENGTH')")
            db.execSQL("INSERT INTO program_exercise VALUES (11,'A',0,'bb_back_squat',1,4,'5/5/5/3',1,NULL,'')")
            db.execSQL("INSERT INTO program_exercise VALUES (12,'B',0,'bb_bench',1,4,'6-10',0,NULL,'')")
            db.execSQL("INSERT INTO exercise_log VALUES ('A',11,'main','[]','2026-10-19',1234)")
            db.execSQL("INSERT INTO workout_session VALUES (5,'A','Old Day A',NULL,1000,180)")
            db.execSQL("INSERT INTO cardio_session VALUES (3,'A','OUTDOOR_RUN',0,'Easy Zone 2',1000,61000,60,1)")
        }
        val db = helper.writableDatabase

        MIGRATION_7_8.migrate(db)

        db.query("SELECT id,name,origin FROM plan").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(PlanEntity.LEGACY_ID, cursor.getLong(0))
            assertEquals(PlanEntity.LEGACY_NAME, cursor.getString(1))
            assertEquals("GENERATED", cursor.getString(2))
        }
        db.query("SELECT planId,dayId,kind FROM program_day ORDER BY position").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("A", cursor.getString(1))
            assertEquals("STRENGTH", cursor.getString(2))
            assertTrue(cursor.moveToNext())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("B", cursor.getString(1))
        }
        db.query("SELECT id,planId FROM program_exercise ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(11L, cursor.getLong(0))
            assertEquals(1L, cursor.getLong(1))
            assertTrue(cursor.moveToNext())
            assertEquals(12L, cursor.getLong(0))
            assertEquals(1L, cursor.getLong(1))
        }
        db.query("SELECT planId,setsJson,checkDate FROM exercise_log").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("[]", cursor.getString(1))
            assertEquals("2026-10-19", cursor.getString(2))
        }
        db.query("SELECT planId,planName FROM workout_session WHERE id=5").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
        db.query("SELECT planId,planName FROM cardio_session WHERE id=3").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
        db.close()
        helper.close()
    }

    @Test
    fun `empty v7 program creates no plan`() {
        val helper = createV7()
        val db = helper.writableDatabase

        MIGRATION_7_8.migrate(db)

        db.query("SELECT COUNT(*) FROM plan").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
        helper.close()
    }

    @Test
    fun `Room accepts the migrated schema`() = runTest {
        createV7 { db ->
            db.execSQL("INSERT INTO program_day VALUES ('A',0,'Day A','Squat',NULL,'STRENGTH')")
        }.also { helper ->
            helper.writableDatabase.close()
            helper.close()
        }

        val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
            .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(listOf("A"), db.programDao().daysForPlan(PlanEntity.LEGACY_ID).map { it.dayId })
        } finally {
            db.close()
        }
    }

    private fun createV7(seed: (SupportSQLiteDatabase) -> Unit = {}): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(7) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V7.forEach(db::execSQL)
                seed(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).also { it.writableDatabase }
    }

    private companion object {
        val V7 = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, PRIMARY KEY(`dayId`))",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_dayId` ON `program_exercise` (`dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `checkDate` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_dayId` ON `exercise_log` (`dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` INTEGER)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `restore_marker` (`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL)",
        )
    }
}
