package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.dao.PersonalRecordRow
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalRecordTest {

    @Test
    fun `ramps cannot replace a record and equal performances keep the earliest date`() {
        val rows = listOf(
            // Oldest first, so the old first-row-wins reducer would have returned this warmup.
            PersonalRecordRow("bb_back_squat", 250.0, 1, 500L, SetKind.RAMP.name),
            PersonalRecordRow("bb_back_squat", 235.0, 5, 1_000L, SetKind.TOP.name),
            PersonalRecordRow("bb_back_squat", 225.0, 5, 2_000L, SetKind.WORK.name),
            PersonalRecordRow("bb_back_squat", 235.0, 5, 3_000L, SetKind.TOP.name),
        )

        assertEquals(
            PersonalRecord("bb_back_squat", 235.0, 5, 1_000L),
            rows.toPersonalRecordsByExercise()["bb_back_squat"],
        )
    }

    @Test
    fun `better sets replace the standing record in first seen exercise order`() {
        val rows = listOf(
            PersonalRecordRow("bb_back_squat", 130.0, 5, 1_000L, SetKind.RAMP.name),
            PersonalRecordRow("bb_bench", 185.0, 5, 1_000L, SetKind.WORK.name),
            PersonalRecordRow("bb_back_squat", 235.0, 5, 2_000L, SetKind.TOP.name),
            PersonalRecordRow("bb_back_squat", 245.0, 3, 2_000L, SetKind.TOP.name),
            PersonalRecordRow("bb_bench", 185.0, 8, 3_000L, SetKind.WORK.name),
            PersonalRecordRow("bb_back_squat", 245.0, 5, 3_000L, SetKind.TOP.name),
        )

        val result = rows.toPersonalRecordsByExercise()

        assertEquals(listOf("bb_back_squat", "bb_bench"), result.keys.toList())
        assertEquals(PersonalRecord("bb_back_squat", 245.0, 5, 3_000L), result["bb_back_squat"])
        assertEquals(PersonalRecord("bb_bench", 185.0, 8, 3_000L), result["bb_bench"])
    }

    @Test
    fun `a change of tracking type uses the newest type`() {
        val rows = listOf(
            PersonalRecordRow("cable_crunch", 0.0, 20, 1_000L, SetKind.WORK.name),
            PersonalRecordRow("cable_crunch", 45.0, 8, 2_000L, SetKind.WORK.name),
            PersonalRecordRow("cable_crunch", 40.0, 10, 3_000L, SetKind.WORK.name),
        )
        assertEquals(
            PersonalRecord("cable_crunch", 45.0, 8, 2_000L),
            rows.toPersonalRecordsByExercise()["cable_crunch"],
        )
    }

    @Test
    fun `returning to a tracking type keeps its older best and date`() {
        val rows = listOf(
            PersonalRecordRow("cable_crunch", 50.0, 8, 1_000L, SetKind.WORK.name),
            PersonalRecordRow("cable_crunch", 0.0, 20, 2_000L, SetKind.WORK.name),
            PersonalRecordRow("cable_crunch", 45.0, 10, 3_000L, SetKind.WORK.name),
        )

        assertEquals(
            PersonalRecord("cable_crunch", 50.0, 8, 1_000L),
            rows.toPersonalRecordsByExercise()["cable_crunch"],
        )
    }

    @Test
    fun `a reps best carries the winning rep count and no seconds`() {
        val rows = listOf(
            PersonalRecordRow("pushup", 0.0, 15, 1_000L, SetKind.WORK.name),
            PersonalRecordRow("pushup", 0.0, 20, 3_000L, SetKind.WORK.name),
        )
        assertEquals(PersonalRecord("pushup", 0.0, 20, 3_000L, seconds = 0), rows.toPersonalRecordsByExercise()["pushup"])
    }

    @Test
    fun `a timed best carries the winning hold in seconds`() {
        val rows = listOf(
            PersonalRecordRow("plank", 0.0, 0, 1_000L, SetKind.WORK.name, seconds = 45),
            PersonalRecordRow("plank", 0.0, 0, 3_000L, SetKind.WORK.name, seconds = 60),
        )
        assertEquals(PersonalRecord("plank", 0.0, 0, 3_000L, seconds = 60), rows.toPersonalRecordsByExercise()["plank"])
    }

    @Test
    fun `a longer hold beats a heavier hold`() {
        val rows = listOf(
            PersonalRecordRow("plank", 25.0, 0, 1_000L, SetKind.WORK.name, seconds = 60),
            PersonalRecordRow("plank", 0.0, 0, 2_000L, SetKind.WORK.name, seconds = 90),
        )
        assertEquals(PersonalRecord("plank", 0.0, 0, 2_000L, seconds = 90), rows.toPersonalRecordsByExercise()["plank"])
    }

    @Test
    fun `an exercise with only ramps is absent`() {
        val rows = listOf(PersonalRecordRow("bb_back_squat", 250.0, 1, 1_000L, SetKind.RAMP.name))
        assertEquals(emptyMap(), rows.toPersonalRecordsByExercise())
    }

    @Test
    fun `unknown kinds and empty sets are skipped`() {
        val rows = listOf(
            PersonalRecordRow("unknown", 300.0, 5, 1_000L, "UNKNOWN"),
            PersonalRecordRow("empty", 0.0, 0, 1_000L, SetKind.WORK.name),
            PersonalRecordRow("bb_bench", 300.0, 5, 1_000L, "UNKNOWN"),
            PersonalRecordRow("bb_bench", 185.0, 5, 2_000L, SetKind.WORK.name),
        )
        assertEquals(
            mapOf("bb_bench" to PersonalRecord("bb_bench", 185.0, 5, 2_000L)),
            rows.toPersonalRecordsByExercise(),
        )
    }

    @Test
    fun `mixed history uses the newest counting tracking type`() {
        val rows = listOf(
            PersonalRecordRow("plank", 25.0, 0, 1_000L, SetKind.RAMP.name, seconds = 60),
            PersonalRecordRow("plank", 0.0, 45, 1_000L, SetKind.WORK.name),
            PersonalRecordRow("plank", 0.0, 0, 2_000L, SetKind.WORK.name, seconds = 90),
            PersonalRecordRow("plank", 0.0, 60, 3_000L, SetKind.WORK.name),
        )
        assertEquals(PersonalRecord("plank", 0.0, 60, 3_000L), rows.toPersonalRecordsByExercise()["plank"])
    }

    @Test
    fun anExerciseWithNoRowsIsAbsent() {
        val result = listOf(PersonalRecordRow("bb_bench", 185.0, 5, 1_000L, SetKind.WORK.name)).toPersonalRecordsByExercise()

        assertEquals(null, result["bb_back_squat"])
    }

    @Test
    fun emptyInputProducesAnEmptyMap() {
        assertEquals(emptyMap(), emptyList<PersonalRecordRow>().toPersonalRecordsByExercise())
    }
}
