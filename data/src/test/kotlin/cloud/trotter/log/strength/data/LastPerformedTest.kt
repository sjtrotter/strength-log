package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.dao.LastPerformedRow
import cloud.trotter.log.strength.data.db.dao.LastPerformedSourceRow
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [toLastPerformedByExercise] is the pure reduction behind the "last time"
 * chip: [SessionDao.lastPerformedRows] returns everything newest-session-first
 * (ties broken by heaviest weight), and this collapses that flat list to one
 * entry per exercise id — the SQL ordering does the real work, so this test
 * only needs to prove "first row wins" and "missing ids are absent".
 */
class LastPerformedTest {

    @Test
    fun `last source names another day or plan and is silent on the shown day`() {
        val rows = listOf(
            LastPerformedSourceRow("squat", Slot.MAIN, 90.0, 5, 0, 10L, "Own", "B", 5L, 0, "WORK"),
            LastPerformedSourceRow("curl", Slot.MAIN, 50.0, 10, 0, 20L, "Other", "A", 4L, 0, "WORK"),
            LastPerformedSourceRow("row", Slot.MAIN, 80.0, 10, 0, 10L, "Own", "A", 3L, 0, "WORK"),
            LastPerformedSourceRow("press", Slot.MAIN, 70.0, 5, 0, 20L, " ", "A", 2L, 0, "WORK"),
            LastPerformedSourceRow("dip", Slot.MAIN, 0.0, 10, 0, null, null, "A", 1L, 0, "WORK"),
        )
        assertEquals(mapOf(
            "squat" to LastSource("B", null),
            "curl" to LastSource("A", "Other"),
            "press" to LastSource("A", null),
            "dip" to LastSource("A", null),
        ), rows.toLastSourcesElsewhere(10L, "A"))
        assertEquals(null, rows.toLastSourcesElsewhere(PlanEntity.LEGACY_ID, "A")["dip"])
    }

    @Test
    fun firstRowPerExerciseIdWins() {
        val rows = listOf(
            LastPerformedRow("bb_back_squat", 235.0, 5),
            LastPerformedRow("bb_back_squat", 175.0, 8), // same session, lighter back-off — ignored
            LastPerformedRow("bb_bench", 185.0, 5),
            LastPerformedRow("bb_back_squat", 220.0, 5), // an older session's TOP — ignored
        )

        val result = rows.toLastPerformedByExercise()

        assertEquals(LastPerformed(235.0, 5), result["bb_back_squat"])
        assertEquals(LastPerformed(185.0, 5), result["bb_bench"])
    }

    @Test
    fun `a reps last-time carries reps and a timed one carries seconds`() {
        val rows = listOf(
            // Same session; the query ordered each exercise's rows so the
            // representative set is first (reps DESC for pushup, seconds DESC for plank).
            LastPerformedRow("pushup", 0.0, 18, 0),
            LastPerformedRow("plank", 0.0, 0, 55),
        )

        val result = rows.toLastPerformedByExercise()

        assertEquals(LastPerformed(0.0, 18, 0), result["pushup"])
        assertEquals(LastPerformed(0.0, 0, 55), result["plank"])
    }

    @Test
    fun anExerciseWithNoRowsIsAbsent() {
        val result = listOf(LastPerformedRow("bb_bench", 185.0, 5)).toLastPerformedByExercise()

        assertEquals(null, result["bb_back_squat"])
    }

    @Test
    fun emptyInputProducesAnEmptyMap() {
        assertEquals(emptyMap(), emptyList<LastPerformedRow>().toLastPerformedByExercise())
    }

    @Test
    fun `last sets by exercise read the newest session only`() {
        val rows = listOf(
            LastPerformedSourceRow("bb_bench", Slot.SS, 235.0, 5, 0, 10L, "Own", "B", 2L, 0, "TOP"),
            LastPerformedSourceRow("bb_bench", Slot.MAIN, 225.0, 5, 0, 10L, "Own", "B", 2L, 2, "WORK"),
            LastPerformedSourceRow("bb_bench", Slot.MAIN, 225.0, 4, 0, 10L, "Own", "B", 2L, 1, "FUTURE"),
            LastPerformedSourceRow("bb_bench", Slot.MAIN, 135.0, 5, 0, 10L, "Own", "B", 2L, 0, "RAMP"),
            LastPerformedSourceRow("bb_bench", Slot.MAIN, 245.0, 5, 0, 10L, "Own", "A", 1L, 0, "TOP"),
        )
        assertEquals(mapOf("bb_bench" to listOf(
            LoggedSet(225.0, 4, SetKind.WORK, done = true),
            LoggedSet(225.0, 5, SetKind.WORK, done = true),
            LoggedSet(235.0, 5, SetKind.TOP, done = true),
        )), rows.toLastSetsByExercise())
        assertEquals(emptyMap(), emptyList<LastPerformedSourceRow>().toLastSetsByExercise())
    }

}
