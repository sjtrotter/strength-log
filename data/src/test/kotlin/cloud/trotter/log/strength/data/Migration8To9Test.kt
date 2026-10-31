package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_8_9
import cloud.trotter.log.strength.data.db.MIGRATION_9_10
import cloud.trotter.log.strength.data.db.MIGRATION_10_11
import cloud.trotter.log.strength.data.db.MIGRATION_11_12
import cloud.trotter.log.strength.data.db.MIGRATION_12_13
import cloud.trotter.log.strength.data.db.MIGRATION_13_14
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
class Migration8To9Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-8-to-9-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test
    fun `migration appends nullable settings without changing existing plan rows`() {
        val helper = createV8()
        val db = helper.writableDatabase

        MIGRATION_8_9.migrate(db)

        db.query("SELECT id,name,origin,position,createdAt,archived,recipeJson,suggestedDayId FROM plan ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("My plan", cursor.getString(1))
            assertEquals("GENERATED", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(1_000L, cursor.getLong(4))
            assertEquals(0, cursor.getInt(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
            assertTrue(cursor.moveToNext())
            assertEquals(2L, cursor.getLong(0))
            assertEquals("Authored", cursor.getString(1))
            assertEquals("AUTHORED", cursor.getString(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals(2_000L, cursor.getLong(4))
            assertEquals(1, cursor.getInt(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
        }
        db.close()
        helper.close()
    }

    @Test
    fun `Room accepts the migrated schema and reads both original plans`() = runTest {
        createV8().also { helper ->
            helper.writableDatabase.close()
            helper.close()
        }
        val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
            .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            .addMigrations(MIGRATION_13_14).allowMainThreadQueries()
            .build()
        try {
            assertEquals(
                listOf(
                    PlanEntity(1, "My plan", "GENERATED", 0, 1_000),
                    PlanEntity(2, "Authored", "AUTHORED", 1, 2_000, archived = true),
                ),
                db.planDao().allPlans(),
            )
            db.planDao().updateRecipe(1, "recipe")
            db.planDao().updateSuggestedDay(1, "B")
            assertEquals("recipe", db.planDao().plan(1)!!.recipeJson)
            assertEquals("B", db.planDao().plan(1)!!.suggestedDayId)
        } finally {
            db.close()
        }
    }

    private fun createV8(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V8.forEach(db::execSQL)
                db.execSQL("INSERT INTO plan VALUES (1,'My plan','GENERATED',0,1000,0)")
                db.execSQL("INSERT INTO plan VALUES (2,'Authored','AUTHORED',1,2000,1)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).also { it.writableDatabase }
    }

    private companion object {
        val V8 = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, PRIMARY KEY(`planId`, `dayId`))",
            "CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_planId_dayId` ON `program_exercise` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `checkDate` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`planId`, `dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_planId_dayId` ON `exercise_log` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` INTEGER, `planId` INTEGER, `planName` TEXT)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `restore_marker` (`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL, `planId` INTEGER, `planName` TEXT)",
            "CREATE TABLE IF NOT EXISTS `plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL)",
        )
    }
}
