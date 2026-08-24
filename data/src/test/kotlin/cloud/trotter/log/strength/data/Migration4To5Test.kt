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
import cloud.trotter.log.strength.data.db.MIGRATION_4_5
import cloud.trotter.log.strength.data.db.MIGRATION_5_6
import cloud.trotter.log.strength.data.db.MIGRATION_6_7
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v4→v5 restore-marker migration (#172). Purely additive, so what needs
 * pinning is that it adds the table without disturbing a device's data, and that
 * the table it adds behaves as the single-row marker the restore path assumes:
 * one row at most, overwritten rather than accumulated, and readable back as the
 * nonce that was written.
 *
 * A device arriving here mid-nothing — the only possibility, since no build
 * before this one could leave a restore outstanding — must land with an empty
 * marker table, which is exactly "no restore is outstanding".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration4To5Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-4-to-5-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `the migration adds an empty marker table and leaves the user's data alone`() = runTest {
        context.deleteDatabase(dbName)
        createV4DatabaseWithRows()

        val db = openMigrated()
        try {
            assertNull("an upgraded device has no restore outstanding", db.restoreMarkerDao().nonce())

            val sessions = db.sessionDao().allSessions()
            assertEquals(1, sessions.size)
            assertEquals(235, sessions.single().bodyweightLb)
            assertEquals(1, db.sessionDao().setsForSession(1L).size)
            assertEquals(listOf("A"), db.programDao().allDays().map { it.dayId })
            assertEquals(1, db.customExerciseDao().allOrdered().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the marker table holds one row and the newest nonce wins`() = runTest {
        context.deleteDatabase(dbName)
        createV4DatabaseWithRows()

        val db = openMigrated()
        try {
            val dao = db.restoreMarkerDao()
            dao.put(RestoreMarkerEntity(nonce = "first"))
            dao.put(RestoreMarkerEntity(nonce = "second"))

            assertEquals("a restore replaces the previous marker, never adds one", "second", dao.nonce())

            dao.clear()
            assertNull(dao.nonce())
        } finally {
            db.close()
        }
    }

    private fun openMigrated(): StrengthDatabase =
        Room.databaseBuilder(context, StrengthDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

    /** Builds the DB at schema v4 (no Room), with a row in each table the
     *  migration must not touch. */
    private fun createV4DatabaseWithRows() {
        val callback = object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V4_STATEMENTS.forEach(db::execSQL)
                db.execSQL(
                    "INSERT INTO program_day (dayId, position, title, emphasisLine, cardioJson) " +
                        "VALUES ('A', 0, 'Day A', 'Squat-focused', NULL)",
                )
                db.execSQL(
                    "INSERT INTO custom_exercise (id, name, pattern, equipmentCsv, perHand, goalStartLb, tracking, targetReps, targetSeconds) " +
                        "VALUES ('custom:1', 'Sled Push', 'PUSH', 'SLED', 0, 90.0, 'WEIGHTED', NULL, NULL)",
                )
                db.execSQL(
                    "INSERT INTO workout_session (id, dayId, dayTitle, startedAt, completedAt, bodyweightLb) " +
                        "VALUES (1, 'A', 'Day A', 900, 1000, 235)",
                )
                db.execSQL(
                    "INSERT INTO session_set (id, sessionId, exerciseId, exerciseName, slot, setIndex, kind, weightLb, reps, done, seconds, startedAtMillis, completedAtMillis) " +
                        "VALUES (1, 1, 'bb_back_squat', 'Barbell Back Squat', 'main', 0, 'TOP', 235.0, 5, 1, 0, NULL, NULL)",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.close() // triggers onCreate at user_version 4
        helper.close()
    }

    private companion object {
        /** The committed v4 schema (schemas/…/4.json), table names substituted. */
        val V4_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, PRIMARY KEY(`dayId`))",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_dayId` ON `program_exercise` (`dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `checkDate` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_dayId` ON `exercise_log` (`dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` INTEGER)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'dc20f0659b6047c9960bb2149a355f23')",
        )
    }
}
