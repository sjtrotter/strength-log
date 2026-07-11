package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.db.dao.LastPerformedRow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [toLastPerformedByExercise] is the pure reduction behind the #14 "last time"
 * chip: [SessionDao.lastPerformedRows] returns everything newest-session-first
 * (ties broken by heaviest weight), and this collapses that flat list to one
 * entry per exercise id — the SQL ordering does the real work, so this test
 * only needs to prove "first row wins" and "missing ids are absent".
 */
class LastPerformedTest {

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
}
