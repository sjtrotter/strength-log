package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_26_27
import cloud.trotter.log.strength.data.db.MIGRATION_27_28
import cloud.trotter.log.strength.data.db.MIGRATION_28_29
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration26To27Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-26-to-27-test.db"
    private val opening = """{"layoff":"SCALED","factor":0.95}"""

    private val rule = SlotRuleJson.encode(
        SlotRule(SlotRole.ACCESSORY, RepTarget.Fixed(10), 2, 0.0125, 1, 0.05, Scheme.STRENGTH),
    )

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun `v26 partner slot preserves its values and gains a nullable rule over two opens`() = runTest {
        createV26().close()
        repeat(2) { reopen ->
            val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
                .addMigrations(MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29).allowMainThreadQueries().build()
            try {
                val expected = RunTrackEntity(11, "open-run", 0, 7, "main", "bb_back_squat", false,
                    "[]", "[]", 3000, note = "Belt", openingJson = opening)
                val track = db.runDao().allTracks().single()
                assertEquals(expected, track)
                assertEquals(WorkoutRunEntity("open-run", 1, "A", "Day A", "Plan", 1000), db.runDao().allRuns().single())
                assertEquals(ProgramDayEntity(1, "A", 0, "Day A", "", null, nextRunId = "open-run"), db.programDao().allDays().single())
                assertEquals(ProgramExerciseEntity(7, 1, "A", 0, "bb_back_squat", "STRAIGHT", 3, "cable_row", "",
                        ruleJson = "rule", partnerRuleJson = if (reopen == 0) null else rule),
                    db.programDao().allExercises().single())
                assertEquals("Plan", db.planDao().plan(1)!!.name)
                if (reopen == 0) {
                    assertNull(db.programDao().exercise(7)!!.partnerRuleJson)
                    db.programDao().setPartnerRule(7, rule)
                    assertEquals(rule, db.programDao().exercise(7)!!.partnerRuleJson)
                }
            } finally { db.close() }
        }
    }

    private fun createV26(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(26) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V26.forEach(db::execSQL)
                db.execSQL("INSERT INTO plan (id,name,origin,position,createdAt,archived,recipeJson,suggestedDayId,onToday,offerStrengthIncreases,offerCardioIncreases) VALUES (1,'Plan','AUTHORED',0,1000,0,NULL,'A',1,1,0)")
                db.execSQL("INSERT INTO program_day (planId,dayId,position,title,emphasisLine,kind,nextRunId) VALUES (1,'A',0,'Day A','','STRENGTH','open-run')")
                db.execSQL("INSERT INTO program_exercise (id,planId,dayId,position,exerciseId,structure,targetSets,note,keepToPlan,supersetExerciseId,ruleJson) VALUES (7,1,'A',0,'bb_back_squat','STRAIGHT',3,'',0,'cable_row','rule')")
                db.execSQL("INSERT INTO workout_run VALUES ('open-run',1,'A','Day A','Plan',1000)")
                db.execSQL("INSERT INTO run_track VALUES (11,'open-run',0,7,'main','bb_back_squat',0,'[]','[]',3000,0,'Belt',?)", arrayOf(opening))
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).also { it.writableDatabase }
    }

    private companion object {
        val V26 = listOf(
            "CREATE TABLE IF NOT EXISTS `program_day` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, `kind` TEXT NOT NULL, `cardioBlockJson` TEXT, `nextRunId` TEXT NOT NULL, `closedAt` INTEGER, `cardioWorkingJson` TEXT, `cardioKeepToPlan` INTEGER NOT NULL DEFAULT 0, `cardioProgressBy` TEXT, `cardioIncreasePercent` INTEGER, `cardioRuleJson` TEXT, PRIMARY KEY(`planId`, `dayId`))",
            "CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)",
            "CREATE TABLE IF NOT EXISTS `program_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `structure` TEXT NOT NULL, `targetSets` INTEGER NOT NULL, `supersetExerciseId` TEXT, `note` TEXT NOT NULL, `prescriptionJson` TEXT, `keepToPlan` INTEGER NOT NULL, `parkedLogsJson` TEXT, `noteUpdatedAt` INTEGER DEFAULT NULL, `ruleJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_planId_dayId` ON `program_exercise` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `exercise_log` (`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, `slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`planId`, `dayId`, `programExerciseId`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_planId_dayId` ON `exercise_log` (`planId`, `dayId`)",
            "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, `dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, `bodyweightLb` REAL, `planId` INTEGER, `planName` TEXT, `runId` TEXT, `plannedJson` TEXT, `note` TEXT NOT NULL, `roughDay` INTEGER NOT NULL DEFAULT 0, `progressionUndoJson` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_session_runId` ON `workout_session` (`runId`)",
            "CREATE TABLE IF NOT EXISTS `session_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, `slot` TEXT NOT NULL, `setIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weightLb` REAL NOT NULL, `reps` INTEGER NOT NULL, `done` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `startedAtMillis` INTEGER, `completedAtMillis` INTEGER, `trackPosition` INTEGER, `plannedIndex` INTEGER, `rir` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_session_set_sessionId` ON `session_set` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `custom_exercise` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `pattern` TEXT NOT NULL, `equipmentCsv` TEXT NOT NULL, `perHand` INTEGER NOT NULL, `goalStartLb` REAL NOT NULL, `tracking` TEXT NOT NULL, `targetReps` INTEGER, `targetSeconds` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `restore_marker` (`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `cardio_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, `mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL, `planId` INTEGER, `planName` TEXT, `activity` TEXT, `note` TEXT NOT NULL DEFAULT '', `effort` TEXT)",
            "CREATE TABLE IF NOT EXISTS `cardio_set_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardioSessionId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `specJson` TEXT NOT NULL, `status` TEXT NOT NULL, `elapsedSeconds` INTEGER NOT NULL, `metres` INTEGER, `distanceSource` TEXT, `metresAtEnd` INTEGER, `secondsAtEnd` INTEGER, `centis` INTEGER, `timingMethod` TEXT, `startProtocol` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_cardio_set_result_cardioSessionId` ON `cardio_set_result` (`cardioSessionId`)",
            "CREATE TABLE IF NOT EXISTS `plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `recipeJson` TEXT, `suggestedDayId` TEXT, `onToday` INTEGER NOT NULL DEFAULT 0, `offerStrengthIncreases` INTEGER NOT NULL DEFAULT 1, `offerCardioIncreases` INTEGER NOT NULL DEFAULT 0, `goalJson` TEXT, `settingsJson` TEXT, `autoApply` INTEGER NOT NULL DEFAULT 0, `lightCycleRemaining` INTEGER NOT NULL DEFAULT 0, `deloadEveryCycles` INTEGER, `cyclesCompleted` INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS `workout_run` (`id` TEXT NOT NULL, `planId` INTEGER, `dayId` TEXT, `dayTitle` TEXT NOT NULL, `planName` TEXT, `startedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `run_track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` TEXT NOT NULL, `position` INTEGER NOT NULL, `programExerciseId` INTEGER, `slot` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `retired` INTEGER NOT NULL, `plannedJson` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `dropped` INTEGER NOT NULL DEFAULT 0, `note` TEXT NOT NULL DEFAULT '', `openingJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_run_track_runId` ON `run_track` (`runId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_run_track_runId_programExerciseId_slot_exerciseId` ON `run_track` (`runId`, `programExerciseId`, `slot`, `exerciseId`)",
        )
    }
}
