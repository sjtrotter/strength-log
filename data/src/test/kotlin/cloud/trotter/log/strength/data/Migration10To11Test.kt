package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_10_11
import cloud.trotter.log.strength.data.db.MIGRATION_11_12
import cloud.trotter.log.strength.data.db.MIGRATION_12_13
import cloud.trotter.log.strength.data.db.MIGRATION_13_14
import cloud.trotter.log.strength.data.db.MIGRATION_14_15
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.CardioDto
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Measure
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration10To11Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-10-to-11-test.db"
    private val legacy = CardioSuggestion("Easy", "Zone 2", false)
    private val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(
        CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
        CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
        CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)),
    ))

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test
    fun `Room opens v10 with null blocks preserves legacy data and persists authored cardio on reopen`() = runTest {
        createV10().also { helper ->
            helper.writableDatabase.close()
            helper.close()
        }
        val original = ProgramDayEntity(1, "A", 0, "Day A", "", CardioDto.encode(legacy))
        val authored = ProgramDayEntity(1, "B", 1, "Track", "", null,
            kind = "CARDIO", cardioBlockJson = CardioBlockJson.encode(block))
        val db = open()
        try {
            assertEquals(listOf(original), db.programDao().allDays().withoutRunIdentity())
            assertNull(db.programDao().allDays().single().cardioBlockJson)
            assertEquals(legacy, CardioDto.decode(db.programDao().allDays().single().cardioJson))
            assertEquals("[]", db.programDao().allLogs().single().setsJson)
            assertEquals("old note", db.programDao().allExercises().single().note)
            val session = db.cardioSessionDao().all().single()
            assertEquals(1500, session.seconds)
            assertEquals(1, session.stepsCompleted)
            assertEquals("LOW_IMPACT", session.mode)
            db.programDao().upsertDay(authored)
            assertEquals(block, db.programDao().day(1, "B")!!.toDomain(emptyList()).cardioBlock)
        } finally {
            db.close()
        }
        val reopened = open()
        try {
            assertEquals(listOf(original, authored), reopened.programDao().allDays().withoutRunIdentity())
            assertEquals(block, CardioBlockJson.decode(reopened.programDao().day(1, "B")!!.cardioBlockJson))
        } finally {
            reopened.close()
        }
    }

    // v13 mints each day's run identity; this test pins the v10 to v11 fields.
    private fun List<ProgramDayEntity>.withoutRunIdentity() = map { it.copy(nextRunId = "", closedAt = null) }

    private fun open(): StrengthDatabase = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
        .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
        .addMigrations(MIGRATION_13_14, MIGRATION_14_15).allowMainThreadQueries()
        .build()

    private fun createV10(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(10) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V10.forEach(db::execSQL)
                db.execSQL("INSERT INTO plan VALUES (1,'My plan','GENERATED',0,1000,0,NULL,'A')")
                db.execSQL("INSERT INTO program_day VALUES (1,'A',0,'Day A','',?,'STRENGTH')",
                    arrayOf(CardioDto.encode(legacy)))
                db.execSQL("INSERT INTO program_exercise VALUES (1,1,'A',0,'bb_back_squat',1,3,'5',1,NULL,'old note',NULL)")
                db.execSQL("INSERT INTO exercise_log VALUES (1,'A',1,'main','[]','2026-10-19',1000)")
                db.execSQL("INSERT INTO cardio_session VALUES (1,'A','LOW_IMPACT',0,'Easy',1000,1501000,1500,1,1,'My plan')")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).also { it.writableDatabase }
    }

    private companion object {
        // DDL from Room's exported v10 schema, before authored cardio existed.
        val V10 = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, PRIMARY KEY(`planId`, `dayId`))",
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
