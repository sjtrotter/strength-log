package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_5_6
import cloud.trotter.log.strength.data.db.StrengthDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration5To6Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-5-to-6-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun `additive migration preserves v5 history and creates cardio history`() = runTest {
        createV5()
        val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
            .addMigrations(MIGRATION_5_6).allowMainThreadQueries().build()
        try {
            assertEquals("Day A", db.sessionDao().allSessions().single().dayTitle)
            assertEquals(emptyList<Any>(), db.cardioSessionDao().all())
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO cardio_session (dayId,mode,hard,label,startedAt,completedAt,seconds,stepsCompleted) " +
                    "VALUES ('A','OUTDOOR_RUN',0,'Easy Zone 2',1000,61000,60,1)",
            )
            assertEquals("Easy Zone 2", db.cardioSessionDao().all().single().label)
        } finally { db.close() }
    }

    private fun createV5() {
        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V5.forEach(db::execSQL)
                db.execSQL("INSERT INTO workout_session VALUES (1,'A','Day A',NULL,1000,180)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        )
        helper.writableDatabase.close(); helper.close()
    }

    private companion object {
        val V5 = listOf(
            "CREATE TABLE program_day (dayId TEXT NOT NULL PRIMARY KEY, position INTEGER NOT NULL, title TEXT NOT NULL, emphasisLine TEXT NOT NULL, cardioJson TEXT)",
            "CREATE TABLE program_exercise (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dayId TEXT NOT NULL, position INTEGER NOT NULL, exerciseId TEXT NOT NULL, isMain INTEGER NOT NULL, targetSets INTEGER NOT NULL, repSchemeLabel TEXT NOT NULL, hasWarmupHint INTEGER NOT NULL, supersetExerciseId TEXT, note TEXT NOT NULL)",
            "CREATE INDEX index_program_exercise_dayId ON program_exercise(dayId)",
            "CREATE TABLE exercise_log (dayId TEXT NOT NULL, programExerciseId INTEGER NOT NULL, slot TEXT NOT NULL, setsJson TEXT NOT NULL, checkDate TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(dayId,programExerciseId,slot))",
            "CREATE INDEX index_exercise_log_dayId ON exercise_log(dayId)",
            "CREATE TABLE workout_session (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dayId TEXT NOT NULL, dayTitle TEXT NOT NULL, startedAt INTEGER, completedAt INTEGER NOT NULL, bodyweightLb INTEGER)",
            "CREATE TABLE session_set (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId INTEGER NOT NULL, exerciseId TEXT NOT NULL, exerciseName TEXT NOT NULL, slot TEXT NOT NULL, setIndex INTEGER NOT NULL, kind TEXT NOT NULL, weightLb REAL NOT NULL, reps INTEGER NOT NULL, done INTEGER NOT NULL, seconds INTEGER NOT NULL, startedAtMillis INTEGER, completedAtMillis INTEGER)",
            "CREATE INDEX index_session_set_sessionId ON session_set(sessionId)",
            "CREATE TABLE custom_exercise (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, pattern TEXT NOT NULL, equipmentCsv TEXT NOT NULL, perHand INTEGER NOT NULL, goalStartLb REAL NOT NULL, tracking TEXT NOT NULL, targetReps INTEGER, targetSeconds INTEGER)",
            "CREATE TABLE restore_marker (id INTEGER NOT NULL PRIMARY KEY, nonce TEXT NOT NULL)",
        )
    }
}
