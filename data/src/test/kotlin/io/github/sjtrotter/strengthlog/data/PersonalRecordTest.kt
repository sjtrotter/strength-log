package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.db.dao.PersonalRecordRow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [toPersonalRecordsByExercise] is the pure reduction behind the profile
 * "Best" chip (docs/briefs/performance-profile.md Phase 1):
 * [SessionDao.personalRecordRows] returns everything heaviest-first (ties
 * broken by more reps, then by earliest achievedAt), and this collapses that
 * flat list to one entry per exercise id — the SQL ordering does the real
 * work, so this test only needs to prove "first row wins" and "missing ids
 * are absent", mirroring [LastPerformedTest].
 */
class PersonalRecordTest {

    @Test
    fun firstRowPerExerciseIdWins() {
        val rows = listOf(
            PersonalRecordRow("bb_back_squat", 245.0, 5, 3_000L),
            PersonalRecordRow("bb_back_squat", 235.0, 5, 1_000L), // lighter — ignored
            PersonalRecordRow("bb_bench", 185.0, 5, 2_000L),
            PersonalRecordRow("bb_back_squat", 245.0, 3, 2_000L), // same weight, fewer reps — ignored
        )

        val result = rows.toPersonalRecordsByExercise()

        assertEquals(PersonalRecord("bb_back_squat", 245.0, 5, 3_000L), result["bb_back_squat"])
        assertEquals(PersonalRecord("bb_bench", 185.0, 5, 2_000L), result["bb_bench"])
    }

    @Test
    fun `a reps best carries the winning rep count and no seconds`() {
        // The query already sorted this exercise's rows (all weight 0) by reps DESC.
        val rows = listOf(
            PersonalRecordRow("pushup", 0.0, 20, 3_000L),
            PersonalRecordRow("pushup", 0.0, 15, 1_000L),
        )
        assertEquals(PersonalRecord("pushup", 0.0, 20, 3_000L, seconds = 0), rows.toPersonalRecordsByExercise()["pushup"])
    }

    @Test
    fun `a timed best carries the winning hold in seconds`() {
        // The query sorted this exercise's rows (weight/reps constant) by seconds DESC.
        val rows = listOf(
            PersonalRecordRow("plank", 0.0, 0, 3_000L, seconds = 60),
            PersonalRecordRow("plank", 0.0, 0, 1_000L, seconds = 45),
        )
        assertEquals(PersonalRecord("plank", 0.0, 0, 3_000L, seconds = 60), rows.toPersonalRecordsByExercise()["plank"])
    }

    @Test
    fun anExerciseWithNoRowsIsAbsent() {
        val result = listOf(PersonalRecordRow("bb_bench", 185.0, 5, 1_000L)).toPersonalRecordsByExercise()

        assertEquals(null, result["bb_back_squat"])
    }

    @Test
    fun emptyInputProducesAnEmptyMap() {
        assertEquals(emptyMap(), emptyList<PersonalRecordRow>().toPersonalRecordsByExercise())
    }
}
