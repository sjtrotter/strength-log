package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_12_13
import cloud.trotter.log.strength.data.db.migration13To14
import cloud.trotter.log.strength.data.db.MIGRATION_14_15
import cloud.trotter.log.strength.data.db.MIGRATION_15_16
import cloud.trotter.log.strength.data.db.MIGRATION_16_17
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration13To14Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-13-to-14-test.db"
    private val migration = migration13To14({ LocalDate.parse("2026-10-19") }, { 9_000L }) { "m-1" }

    @After fun cleanup() { context.deleteDatabase(name) }

    /** [F] */
    @Test fun `today becomes a run and the surplus day is archived`() = runTest {
        createV13().also { it.writableDatabase.close(); it.close() }
        val db = open()
        try {
            val run = db.runDao().openRun()!!
            assertEquals("id-A", run.id)
            assertEquals(4_000L, run.startedAt)
            val tracks = db.runDao().tracks(run.id)
            assertEquals(listOf(1L, 2L), tracks.map { it.programExerciseId })
            val actual = SetJson.decodeSets(tracks[0].setsJson).single()
            assertTrue(actual.done)
            assertEquals(0, actual.plannedIndex)
            assertFalse(SetJson.decodeSets(tracks[0].plannedJson).single().done)
            assertFalse(SetJson.decodeSets(tracks[1].setsJson).single().done)
            val session = db.sessionDao().allSessions().single()
            assertEquals("B", session.dayId)
            assertEquals(3_000L, session.completedAt)
            assertNull(session.runId)
            val set = db.sessionDao().allSessionSets().single()
            assertEquals(session.id, set.sessionId)
            assertEquals("Barbell Row", set.exerciseName)
            assertEquals(95.0, set.weightLb, 0.0)
            assertEquals(10, set.reps)
            assertTrue(set.done)
            val day = db.programDao().day(1, "B")!!
            assertEquals("m-1", day.nextRunId)
            assertEquals(9_000L, day.closedAt)
            assertWorking(db)
        } finally { db.close() }
    }

    /** [F] */
    @Test fun `an existing open run keeps its day attached and archives only the other day`() = runTest {
        createV13(existing = true).also { it.writableDatabase.close(); it.close() }
        val db = open()
        try {
            assertEquals("id-A", db.runDao().openRun()!!.id)
            assertEquals(setOf("B"), db.sessionDao().allSessions().map { it.dayId }.toSet())
            assertTrue(db.runDao().allTracks().isEmpty())
            assertEquals("id-A", db.programDao().day(1, "A")!!.nextRunId)
            assertNull(db.programDao().day(1, "A")!!.closedAt)
            assertEquals("m-1", db.programDao().day(1, "B")!!.nextRunId)
            assertEquals(9_000L, db.programDao().day(1, "B")!!.closedAt)
            assertWorking(db)
        } finally { db.close() }
    }

    /** [F] */
    @Test fun `yesterday's checks create no run or archive`() = runTest {
        createV13(yesterday = true).also { it.writableDatabase.close(); it.close() }
        val db = open()
        try {
            assertNull(db.runDao().openRun())
            assertTrue(db.sessionDao().allSessions().isEmpty())
            assertTrue(db.programDao().allDays().all { it.closedAt == null })
            assertWorking(db)
        } finally { db.close() }
    }

    /** [F] */
    @Test fun `run start fallback ignores untouched rows updated ten days earlier`() = runTest {
        createV13(fallback = true).also { it.writableDatabase.close(); it.close() }
        val db = open()
        try {
            assertEquals(6_000L, db.runDao().openRun()!!.startedAt)
            assertWorking(db)
        } finally { db.close() }
    }

    /** [F] */
    @Test fun `archive timestamp fallback ignores untouched rows`() = runTest {
        createV13(fallback = true).also {
            it.writableDatabase.execSQL("INSERT INTO workout_run VALUES ('id-B',1,'B','Day B','My plan',1000)")
            it.writableDatabase.close()
            it.close()
        }
        val db = open()
        try {
            val session = db.sessionDao().allSessions().first { it.dayId == "A" }
            assertEquals(6_000L, session.startedAt)
            assertEquals(6_000L, session.completedAt)
            assertWorking(db)
        } finally { db.close() }
    }

    private suspend fun assertWorking(db: StrengthDatabase) {
        assertEquals(3, db.programDao().allLogs().size)
        db.programDao().allLogs().flatMap { SetJson.decodeSets(it.setsJson) }.forEach {
            assertFalse(it.done)
            assertNull(it.startedAtMillis)
            assertNull(it.completedAtMillis)
        }
        db.openHelper.readableDatabase.query("PRAGMA table_info(exercise_log)").use { c ->
            val columns = buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            assertFalse("checkDate" in columns)
        }
    }

    private fun open(): StrengthDatabase = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
        .addMigrations(migration, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17).allowMainThreadQueries().build()

    private fun createV13(existing: Boolean = false, yesterday: Boolean = false, fallback: Boolean = false): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(13) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V12.forEach(db::execSQL)
                MIGRATION_12_13.migrate(db)
                db.execSQL("INSERT INTO plan VALUES (1,'My plan','GENERATED',0,1000,0,NULL,'A')")
                for ((id, position) in listOf("A" to 0, "B" to 1)) {
                    db.execSQL("INSERT INTO program_day VALUES (1, ?, ?, ?, '', NULL, 'STRENGTH', NULL, ?, NULL)",
                        arrayOf<Any>(id, position, "Day $id", "id-$id"))
                }
                for ((id, day, exercise) in listOf(Triple(1, "A", "bb_back_squat"), Triple(2, "A", "bb_bench"), Triple(3, "B", "bb_row"))) {
                    db.execSQL("INSERT INTO program_exercise VALUES (?,1,?,?,?,1,1,'5',0,NULL,'',NULL)",
                        arrayOf<Any>(id, day, if (id == 2) 1 else 0, exercise))
                }
                val date = if (yesterday) "2026-10-18" else "2026-10-19"
                fun log(day: String, slot: Long, set: LoggedSet, stamp: String, updated: Long) {
                    db.execSQL("INSERT INTO exercise_log VALUES (1, ?, ?, 'main', ?, ?, ?)",
                        arrayOf<Any>(day, slot, SetJson.encodeSets(listOf(set)), stamp, updated))
                }
                log("A", 1, LoggedSet(235.0, 5, SetKind.TOP, true, startedAtMillis = if (fallback) null else 4_000, completedAtMillis = if (fallback) null else 5_000), date, 6_000)
                log("A", 2, LoggedSet(195.0, 5, SetKind.WORK, true), "", if (fallback) 6_000 - 10 * 86_400_000L else 6_000)
                log("B", 3, LoggedSet(95.0, 10, SetKind.WORK, true, completedAtMillis = 3_000), date, 3_500)
                if (existing) db.execSQL("INSERT INTO workout_run VALUES ('id-A',1,'A','Day A','My plan',1000)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        )
    }

    private companion object {
        val V12 = listOf(
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
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL, `planId` INTEGER, `planName` TEXT, `activity` TEXT)",
            "CREATE TABLE IF NOT EXISTS `cardio_set_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardioSessionId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `specJson` TEXT NOT NULL, `status` TEXT NOT NULL, `elapsedSeconds` INTEGER NOT NULL, `metres` INTEGER, `distanceSource` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_cardio_set_result_cardioSessionId` ON `cardio_set_result` (`cardioSessionId`)",
            "CREATE TABLE IF NOT EXISTS `plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `recipeJson` TEXT, `suggestedDayId` TEXT)",
        )
    }
}
