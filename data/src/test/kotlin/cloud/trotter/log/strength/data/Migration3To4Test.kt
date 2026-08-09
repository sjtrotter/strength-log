package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_1_2
import cloud.trotter.log.strength.data.db.MIGRATION_2_3
import cloud.trotter.log.strength.data.db.MIGRATION_3_4
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v3→v4 nullable-bodyweight migration (#171). It rebuilds `workout_session`
 * (SQLite can't relax NOT NULL in place), so this opens a hand-built v3 DB
 * holding both kinds of row — a real session and a CSV-imported one written with
 * the fabricated `bodyweightLb = 0` — runs the migration, and asserts the 0
 * became NULL while the real number and every other column survived. It then
 * writes a fresh session to prove the rebuilt table still autoincrements past
 * the ids it inherited.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration3To4Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-3-to-4-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `the migration keeps recorded bodyweights and turns the imported zeroes into unknown`() = runTest {
        context.deleteDatabase(dbName)
        createV3DatabaseWithRows()

        val db = Room.databaseBuilder(context, StrengthDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            val sessions = db.sessionDao().allSessions()
            assertEquals(2, sessions.size)

            val logged = sessions.first { it.id == 1L }
            assertEquals(235, logged.bodyweightLb)
            assertEquals("A", logged.dayId)
            assertEquals("Day A", logged.dayTitle)
            assertEquals(900L, logged.startedAt)
            assertEquals(1000L, logged.completedAt)

            // The only way a session ever held 0: a CSV import, which has no
            // bodyweight column. That is "unknown", not "weighed nothing".
            val imported = sessions.first { it.id == 2L }
            assertNull(imported.bodyweightLb)
            assertEquals("csv:Day A", imported.dayId)
            assertNull(imported.startedAt)

            // Sets key on session ids, so the rebuild must not disturb them.
            assertEquals(1, db.sessionDao().setsForSession(1L).size)
            assertEquals(1, db.sessionDao().setsForSession(2L).size)

            // The rebuilt table still autoincrements, so a new session can't collide
            // with an id a live log or an existing set already points at.
            val newId = db.sessionDao().insertSession(
                WorkoutSessionEntity(
                    id = 0,
                    dayId = "B",
                    dayTitle = "Day B",
                    startedAt = null,
                    completedAt = 5000L,
                    bodyweightLb = null,
                ),
            )
            assertTrue("new session id $newId must exceed the migrated ids", newId > 2L)
            assertNull(db.sessionDao().sessionById(newId)!!.bodyweightLb)
        } finally {
            db.close()
        }
    }

    /** Builds the DB at schema v3 (no Room), with one logged session and one
     *  CSV-imported session written the pre-#171 way. */
    private fun createV3DatabaseWithRows() {
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V3_STATEMENTS.forEach(db::execSQL)
                db.execSQL(
                    "INSERT INTO workout_session (id, dayId, dayTitle, startedAt, completedAt, bodyweightLb) " +
                        "VALUES (1, 'A', 'Day A', 900, 1000, 235)",
                )
                db.execSQL(
                    "INSERT INTO workout_session (id, dayId, dayTitle, startedAt, completedAt, bodyweightLb) " +
                        "VALUES (2, 'csv:Day A', 'Day A', NULL, 2000, 0)",
                )
                db.execSQL(
                    "INSERT INTO session_set (id, sessionId, exerciseId, exerciseName, slot, setIndex, kind, weightLb, reps, done, seconds, startedAtMillis, completedAtMillis) " +
                        "VALUES (1, 1, 'bb_back_squat', 'Barbell Back Squat', 'main', 0, 'TOP', 235.0, 5, 1, 0, NULL, NULL)",
                )
                db.execSQL(
                    "INSERT INTO session_set (id, sessionId, exerciseId, exerciseName, slot, setIndex, kind, weightLb, reps, done, seconds, startedAtMillis, completedAtMillis) " +
                        "VALUES (2, 2, 'bb_back_squat', 'Barbell Back Squat', 'main', 0, 'WORK', 225.0, 5, 1, 0, NULL, NULL)",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.close() // triggers onCreate at user_version 3
        helper.close()
    }

    private companion object {
        /** The committed v3 schema (schemas/…/3.json), table names substituted. */
        val V3_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, PRIMARY KEY(`dayId`))",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_dayId` ON `program_exercise` (`dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `checkDate` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_dayId` ON `exercise_log` (`dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'd89d0d7906e92bb92eafc0045850000c')",
        )
    }
}
