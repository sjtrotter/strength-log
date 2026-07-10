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
    fun anExerciseWithNoRowsIsAbsent() {
        val result = listOf(PersonalRecordRow("bb_bench", 185.0, 5, 1_000L)).toPersonalRecordsByExercise()

        assertEquals(null, result["bb_back_squat"])
    }

    @Test
    fun emptyInputProducesAnEmptyMap() {
        assertEquals(emptyMap(), emptyList<PersonalRecordRow>().toPersonalRecordsByExercise())
    }
}
