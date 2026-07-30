package io.github.sjtrotter.strengthlog.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.db.MIGRATION_1_2
import io.github.sjtrotter.strengthlog.data.db.MIGRATION_2_3
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.serialization.SetJson
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v2→v3 per-set timing migration (#85) must be purely additive. This opens a
 * hand-built v2 DB carrying a real archived set and a real live log, runs the
 * migration, and asserts the two new columns read NULL ("not observed" — never 0,
 * which any later active-time math would read as "started at the epoch") while every
 * pre-existing value survives. It then writes a stamped set to prove the new columns
 * round-trip, and re-reads the untouched live log to prove the JSON side needs no
 * migration at all (the `SetDto` stamps default to null).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration2To3Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-2-to-3-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `additive migration nulls the new timing columns and loses no data`() = runTest {
        context.deleteDatabase(dbName)
        createV2DatabaseWithRows()

        val db = Room.databaseBuilder(context, StrengthDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val sets = db.sessionDao().allSessionSets()
            assertEquals(1, sets.size)
            // Everything already archived survives verbatim.
            assertEquals("plank", sets[0].exerciseId)
            assertEquals(45, sets[0].seconds)
            assertEquals(0.0, sets[0].weightLb, 0.0)
            assertEquals(1, sets[0].reps)
            // History predating per-set timing has no timing — not zero timing.
            assertNull(sets[0].startedAtMillis)
            assertNull(sets[0].completedAtMillis)

            // The live log is JSON and untouched by the migration: it still decodes,
            // with the stamps defaulting to "not observed".
            val log = db.programDao().logsForDay("A").single()
            val liveSets = SetJson.decodeSets(log.setsJson)
            assertEquals(1, liveSets.size)
            assertEquals(235.0, liveSets[0].weightLb, 0.0)
            assertNull(liveSets[0].startedAtMillis)
            assertNull(liveSets[0].completedAtMillis)

            // The new columns are writable and read back exactly.
            db.sessionDao().insertSets(
                listOf(
                    SessionSetEntity(
                        id = 0,
                        sessionId = 1,
                        exerciseId = "bb_back_squat",
                        exerciseName = "Barbell Back Squat",
                        slot = "main",
                        setIndex = 1,
                        kind = "TOP",
                        weightLb = 235.0,
                        reps = 5,
                        done = true,
                        seconds = 0,
                        startedAtMillis = 1_700_000_000_000L,
                        completedAtMillis = 1_700_000_042_000L,
                    ),
                ),
            )
            val stamped = db.sessionDao().allSessionSets().first { it.exerciseId == "bb_back_squat" }
            assertEquals(1_700_000_000_000L, stamped.startedAtMillis)
            assertEquals(1_700_000_042_000L, stamped.completedAtMillis)
        } finally {
            db.close()
        }
    }

    /** Builds the DB at schema v2 (no Room), with one archived plank set and one
     *  live log written in the pre-#85 `setsJson` shape. */
    private fun createV2DatabaseWithRows() {
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V2_STATEMENTS.forEach(db::execSQL)
                db.execSQL(
                    "INSERT INTO workout_session (id, dayId, dayTitle, startedAt, completedAt, bodyweightLb) " +
                        "VALUES (1, 'A', 'Day A', 900, 1000, 235)",
                )
                db.execSQL(
                    "INSERT INTO session_set (id, sessionId, exerciseId, exerciseName, slot, setIndex, kind, weightLb, reps, done, seconds) " +
                        "VALUES (1, 1, 'plank', 'Plank', 'main', 0, 'WORK', 0.0, 1, 1, 45)",
                )
                db.execSQL(
                    "INSERT INTO exercise_log (dayId, programExerciseId, slot, setsJson, checkDate, updatedAt) " +
                        """VALUES ('A', 1, 'main', '[{"weightLb":235.0,"reps":5,"kind":"TOP","done":false}]', '2026-07-27', 1000)""",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.close() // triggers onCreate at user_version 2
        helper.close()
    }

    private companion object {
        /** The committed v2 schema (schemas/…/2.json), table names substituted. */
        val V2_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, PRIMARY KEY(`dayId`))",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_dayId` ON `program_exercise` (`dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `checkDate` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_dayId` ON `exercise_log` (`dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '44c2ded7debb5898796dbeee3b410901')",
        )
    }
}
