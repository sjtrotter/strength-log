package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.dao.LastPerformedRow
import cloud.trotter.log.strength.data.db.dao.LastPerformedSourceRow
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.Slot
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
    fun `slot last prefers its plan day and slot over newer performances`() {
        val rows = listOf(
            LastPerformedSourceRow("squat", Slot.MAIN, 90.0, 3, 0, 20L, "Other", "A"),
            LastPerformedSourceRow("squat", Slot.MAIN, 80.0, 5, 0, 10L, "Own", "B"),
            LastPerformedSourceRow("squat", Slot.MAIN, 70.0, 5, 0, 10L, "Own", "A"),
            LastPerformedSourceRow("squat", Slot.MAIN, 65.0, 8, 0, 10L, "Own", "A"),
            LastPerformedSourceRow("squat", Slot.SS, 50.0, 10, 30, 10L, "Own", "A"),
        )
        val result = rows.toLastPerformedForSlots(10L, "A")
        assertEquals(SlotLast(LastPerformed(70.0, 5), true, 10L, "Own", "A"), result["squat" to Slot.MAIN])
        assertEquals(SlotLast(LastPerformed(50.0, 10, 30), true, 10L, "Own", "A"), result["squat" to Slot.SS])
    }

    @Test
    fun `slot last returns both slots with a labeled fallback from any slot`() {
        val rows = listOf(
            LastPerformedSourceRow("curl", Slot.SS, 50.0, 10, 0, 20L, "Other", "B"),
            LastPerformedSourceRow("curl", Slot.MAIN, 40.0, 8, 0, 10L, "Own", "B"),
        )
        val result = rows.toLastPerformedForSlots(10L, "A")
        assertEquals(2, result.size)
        for (slot in listOf(Slot.MAIN, Slot.SS)) {
            assertEquals(SlotLast(LastPerformed(50.0, 10), false, 20L, "Other", "B"), result["curl" to slot])
        }
    }

    @Test
    fun `null plan history belongs to the legacy plan alone`() {
        val rows = listOf(LastPerformedSourceRow("squat", Slot.MAIN, 70.0, 5, 0, null, null, "A"))
        val legacy = rows.toLastPerformedForSlots(PlanEntity.LEGACY_ID, "A")
        assertEquals(SlotLast(LastPerformed(70.0, 5), true, null, null, "A"), legacy["squat" to Slot.MAIN])
        val other = rows.toLastPerformedForSlots(20L, "A")
        assertEquals(SlotLast(LastPerformed(70.0, 5), false, null, null, "A"), other["squat" to Slot.MAIN])
        assertEquals(false, legacy["squat" to Slot.SS]?.sameSlot)
    }

    @Test
    fun `empty slot source rows produce an empty map`() {
        assertEquals(emptyMap(), emptyList<LastPerformedSourceRow>().toLastPerformedForSlots(10L, "A"))
    }

}
