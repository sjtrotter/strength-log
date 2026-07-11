package io.github.sjtrotter.strengthlog.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.db.MIGRATION_1_2
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v1→v2 tracking-types migration must be purely additive: it adds columns
 * with defaults and touches no existing value. This opens a hand-built v1 DB with
 * a real `session_set` and `custom_exercise` row, runs [MIGRATION_1_2], and
 * asserts the new columns default (seconds 0, tracking WEIGHTED, null targets)
 * while every pre-existing value survives — in particular the plank row's reps
 * are *not* silently reinterpreted here (that is the separate one-shot fixup).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration1To2Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-1-to-2-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `additive migration defaults new columns and loses no data`() = runTest {
        context.deleteDatabase(dbName)
        createV1DatabaseWithRows()

        val db = Room.databaseBuilder(context, StrengthDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val sets = db.sessionDao().allSessionSets()
            assertEquals(1, sets.size)
            // Existing values preserved verbatim — the migration carries nothing.
            assertEquals("plank", sets[0].exerciseId)
            assertEquals(45, sets[0].reps)
            assertEquals(0.0, sets[0].weightLb, 0.0)
            // The new column reads its DEFAULT.
            assertEquals(0, sets[0].seconds)

            val customs = db.customExerciseDao().getAll()
            assertEquals(1, customs.size)
            assertEquals(95.0, customs[0].goalStartLb, 0.0)
            assertEquals("WEIGHTED", customs[0].tracking)
            assertNull(customs[0].targetReps)
            assertNull(customs[0].targetSeconds)
        } finally {
            db.close()
        }
    }

    /** Builds the DB at schema v1 (no Room), inserting one plank session set and
     *  one custom exercise, so the migration has real rows to preserve. */
    private fun createV1DatabaseWithRows() {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V1_STATEMENTS.forEach(db::execSQL)
                db.execSQL(
                    "INSERT INTO workout_session (id, dayId, dayTitle, startedAt, completedAt, bodyweightLb) " +
                        "VALUES (1, 'A', 'Day A', NULL, 1000, 235)",
                )
                db.execSQL(
                    "INSERT INTO session_set (id, sessionId, exerciseId, exerciseName, slot, setIndex, kind, weightLb, reps, done) " +
                        "VALUES (1, 1, 'plank', 'Plank', 'main', 0, 'WORK', 0.0, 45, 1)",
                )
                db.execSQL(
                    "INSERT INTO custom_exercise (id, name, pattern, equipmentCsv, perHand, goalStartLb) " +
                        "VALUES ('custom_abc', 'My Machine', 'H_PUSH', 'MACHINE', 0, 95.0)",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.close() // triggers onCreate at user_version 1
        helper.close()
    }

    private companion object {
        /** The committed v1 schema (schemas/…/1.json), table names substituted. */
        val V1_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, PRIMARY KEY(`dayId`))",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_dayId` ON `program_exercise` (`dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `checkDate` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_dayId` ON `exercise_log` (`dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '643953d8187d30dfa672963584a92d76')",
        )
    }
}
