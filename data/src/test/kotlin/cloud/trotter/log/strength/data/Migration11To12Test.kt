package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_11_12
import cloud.trotter.log.strength.data.db.MIGRATION_12_13
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.CardioSetResultEntity
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration11To12Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-11-to-12-test.db"
    private val original = CardioSessionEntity(
        id = 7, dayId = "A", mode = "LOW_IMPACT", hard = false, label = "Easy",
        startedAt = 1_000, completedAt = 1_501_000, seconds = 1_500, stepsCompleted = 1,
        planId = 1, planName = "My plan",
    )

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test
    fun `Room opens v11 with unchanged summaries and persists activity and results on reopen`() = runTest {
        createV11().also { it.writableDatabase.close(); it.close() }
        val storedSet: CardioSetResultEntity
        val db = open()
        val newSession = original.copy(id = 8, startedAt = 2_000, activity = "ROWER")
        try {
            assertEquals(listOf(original), db.cardioSessionDao().all())
            assertNull(db.cardioSessionDao().byId(7)!!.activity)
            assertTrue(db.cardioSetResultDao().all().isEmpty())
            assertEquals("[]", db.programDao().allLogs().single().setsJson)
            db.openHelper.readableDatabase.query("PRAGMA index_list(`cardio_set_result`)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("index_cardio_set_result_cardioSessionId", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_list(`cardio_set_result`)").use { cursor ->
                assertEquals(0, cursor.count)
            }
            assertEquals(8L, db.cardioSessionDao().insert(newSession))
            db.cardioSetResultDao().insertAll(listOf(CardioSetResultEntity(
                cardioSessionId = 8, position = 0,
                specJson = CardioBlockJson.encodeSet(CardioSetSpec(CardioRole.WORK, Measure.Distance(400))),
                status = "SHORTENED", elapsedSeconds = 100, metres = 380, distanceSource = "MANUAL",
            )))
            storedSet = db.cardioSetResultDao().forSession(8).single()
            assertTrue(storedSet.id > 0)
        } finally { db.close() }
        val reopened = open()
        try {
            assertEquals(listOf(original, newSession), reopened.cardioSessionDao().all())
            assertEquals(listOf(storedSet), reopened.cardioSetResultDao().forSession(8))
            assertTrue(reopened.cardioSetResultDao().forSession(7).isEmpty())
        } finally { reopened.close() }
    }

    private fun open(): StrengthDatabase = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
        .addMigrations(MIGRATION_11_12, MIGRATION_12_13)
        .allowMainThreadQueries()
        .build()

    private fun createV11(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(11) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V11.forEach(db::execSQL)
                db.execSQL("INSERT INTO plan VALUES (1,'My plan','GENERATED',0,1000,0,NULL,'A')")
                db.execSQL("INSERT INTO program_day VALUES (1,'A',0,'Day A','',NULL,'STRENGTH',NULL)")
                db.execSQL("INSERT INTO program_exercise VALUES (1,1,'A',0,'bb_back_squat',1,3,'5',1,NULL,'old note',NULL)")
                db.execSQL("INSERT INTO exercise_log VALUES (1,'A',1,'main','[]','2026-10-19',1000)")
                db.execSQL("INSERT INTO cardio_session VALUES (7,'A','LOW_IMPACT',0,'Easy',1000,1501000,1500,1,1,'My plan')")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).also { it.writableDatabase }
    }

    private companion object {
        // DDL from Room's exported v11 schema. Room validates v12 when opened above.
        val V11 = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, `cardioBlockJson` TEXT, PRIMARY KEY(`planId`, `dayId`))",
            "CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL, `prescriptionJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_planId_dayId` ON `program_exercise` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `checkDate` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`planId`, `dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_planId_dayId` ON `exercise_log` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` INTEGER, `planId` INTEGER, `planName` TEXT)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `restore_marker` (`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL, `planId` INTEGER, `planName` TEXT)",
            "CREATE TABLE IF NOT EXISTS `plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `recipeJson` TEXT, `suggestedDayId` TEXT)",
        )
    }
}
