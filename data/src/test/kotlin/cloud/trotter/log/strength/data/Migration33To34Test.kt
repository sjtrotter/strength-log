package cloud.trotter.log.strength.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_24_25
import cloud.trotter.log.strength.data.db.MIGRATION_25_26
import cloud.trotter.log.strength.data.db.MIGRATION_26_27
import cloud.trotter.log.strength.data.db.MIGRATION_27_28
import cloud.trotter.log.strength.data.db.MIGRATION_28_29
import cloud.trotter.log.strength.data.db.MIGRATION_29_30
import cloud.trotter.log.strength.data.db.MIGRATION_30_31
import cloud.trotter.log.strength.data.db.MIGRATION_31_32
import cloud.trotter.log.strength.data.db.MIGRATION_32_33
import cloud.trotter.log.strength.data.db.MIGRATION_33_34
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.TestResultEntity
import cloud.trotter.log.strength.data.serialization.TestEventJson
import cloud.trotter.log.strength.domain.standards.TestEvents
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration33To34Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-33-to-34-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun `v33 rows stay intact and test results persist over two opens`() = runTest {
        val helper = V24Fixture.create(context, name, version = 33) { sql ->
            V24Fixture.populate(sql)
            listOf(MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29,
                MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33).forEach { it.migrate(sql) }
            sql.execSQL("UPDATE session_set SET leftWeightLb = 20.0, leftReps = 8, leftSeconds = 0, rightWeightLb = 20.0, rightReps = 7, rightSeconds = 0")
            sql.execSQL("INSERT INTO cardio_route VALUES (12,'route')")
            sql.execSQL("INSERT INTO restore_marker VALUES (1,'nonce')")
        }
        val tables = V24Fixture.tables + "cardio_route"
        val before = tables.associateWith { V24Fixture.rows(helper.writableDatabase, it) }
        val sequence = V24Fixture.rows(helper.writableDatabase, "sqlite_sequence")
        helper.close()
        var result = TestResultEntity(completedAt = 2000, planId = 1, dayId = "A", runId = "open-run",
            programExerciseId = 7, setIndex = 0, exerciseId = "hand_release_pushup", eventId = "aft_hrp",
            eventVersion = 1, windowSeconds = 120, count = 27, seconds = null, stop = "TIME_UP",
            rulesJson = TestEventJson.encode(TestEvents.get("aft_hrp")))
        repeat(2) { reopen ->
            val db = Room.databaseBuilder(context, StrengthDatabase::class.java, name)
                .addMigrations(MIGRATION_33_34).allowMainThreadQueries().build()
            try {
                val sql = db.openHelper.writableDatabase
                before.forEach { (table, rows) ->
                    val expected = if (table == "plan") rows.map { it + listOf<String?>(null) } else rows
                    assertEquals(expected, V24Fixture.rows(sql, table), table)
                }
                assertEquals(sequence, V24Fixture.rows(sql, "sqlite_sequence").filterNot { it[0] == "test_result" })
                assertNull(db.planDao().plan(1)!!.testGoalJson)
                sql.query("PRAGMA table_info(test_result)").use { cursor ->
                    val columns = buildList {
                        while (cursor.moveToNext()) add(listOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("notnull")).toString(),
                            cursor.getInt(cursor.getColumnIndexOrThrow("pk")).toString(),
                        ))
                    }
                    val names = listOf("id", "completedAt", "planId", "dayId", "runId", "programExerciseId", "setIndex",
                        "exerciseId", "eventId", "eventVersion", "windowSeconds", "count", "seconds", "stop", "rulesJson")
                    val text = setOf("dayId", "runId", "exerciseId", "eventId", "stop", "rulesJson")
                    val required = setOf("id", "completedAt", "exerciseId", "eventId", "eventVersion", "stop", "rulesJson")
                    assertEquals(names.map { column -> listOf(column, if (column in text) "TEXT" else "INTEGER",
                        if (column in required) "1" else "0", if (column == "id") "1" else "0") }, columns)
                }
                sql.query("PRAGMA index_list(test_result)").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("index_test_result_eventId_eventVersion", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                if (reopen == 0) result = result.copy(id = db.testResultDao().insert(result))
                assertEquals(listOf(result), db.testResultDao().all())
            } finally { db.close() }
        }
    }
}
