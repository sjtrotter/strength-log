package io.github.sjtrotter.strengthlog.wear.data

import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchDay
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watch's outbound-queue drain rule (§11.4). A queued edit is re-sent until a
 * snapshot reflects it (that snapshot is the only ack), and dropped once it is —
 * or once the phone has moved to a different suggested day.
 */
class PendingEditsTest {

    private fun snapshot(
        dayId: String = "A",
        sets: List<WatchSet> = listOf(
            WatchSet(235.0, 5, "TOP", done = false),
            WatchSet(175.0, 8, "BACKOFF", done = false),
        ),
        ssSets: List<WatchSet> = emptyList(),
    ) = WatchSnapshot(
        revision = 1L,
        suggestedDayId = dayId,
        day = WatchDay(
            dayId = dayId,
            title = "Day",
            accentIndex = 0,
            exercises = listOf(
                WatchExercise(1L, "main", "Squat", 235.0, false, if (ssSets.isEmpty()) null else "Partner", sets, ssSets),
            ),
        ),
        unit = "lb",
    )

    private fun delta(
        setIndex: Int = 0,
        weightLb: Double? = null,
        reps: Int? = null,
        done: Boolean? = null,
        dayId: String = "A",
        slot: String = "main",
        editedAtMillis: Long = 1L,
    ) = SetEditDelta(
        dayId = dayId,
        programExerciseId = 1L,
        slot = slot,
        setIndex = setIndex,
        weightLb = weightLb,
        reps = reps,
        done = done,
        editedAtMillis = editedAtMillis,
    )

    @Test
    fun `a not-yet-reflected edit stays queued`() {
        val pending = listOf(delta(setIndex = 0, done = true))
        // Snapshot still shows done=false at index 0 → the edit hasn't landed.
        assertEquals(pending, PendingEdits.reconcile(pending, snapshot()))
    }

    @Test
    fun `a reflected done edit drains out`() {
        val reflected = snapshot(
            sets = listOf(WatchSet(235.0, 5, "TOP", done = true), WatchSet(175.0, 8, "BACKOFF", done = false)),
        )
        assertTrue(PendingEdits.reconcile(listOf(delta(setIndex = 0, done = true)), reflected).isEmpty())
    }

    @Test
    fun `a reflected weight edit drains out`() {
        val reflected = snapshot(
            sets = listOf(WatchSet(245.0, 5, "TOP", done = false), WatchSet(185.0, 8, "BACKOFF", done = false)),
        )
        assertTrue(PendingEdits.reconcile(listOf(delta(setIndex = 0, weightLb = 245.0)), reflected).isEmpty())
    }

    @Test
    fun `an edit for a day the phone has moved past is abandoned`() {
        // Watch queued an edit to day A; the phone now suggests day B.
        assertTrue(PendingEdits.reconcile(listOf(delta(dayId = "A", done = true)), snapshot(dayId = "B")).isEmpty())
    }

    @Test
    fun `a partner-track edit reconciles against ssSets`() {
        val reflected = snapshot(
            sets = listOf(WatchSet(235.0, 5, "TOP", done = false), WatchSet(175.0, 8, "BACKOFF", done = false)),
            ssSets = listOf(WatchSet(50.0, 15, "WORK", done = false), WatchSet(50.0, 14, "WORK", done = false)),
        )
        val edit = delta(slot = "ss", setIndex = 0, reps = 15)
        assertTrue(PendingEdits.reconcile(listOf(edit), reflected).isEmpty())
        val notYet = delta(slot = "ss", setIndex = 0, reps = 99)
        assertEquals(listOf(notYet), PendingEdits.reconcile(listOf(notYet), reflected))
    }

    @Test
    fun `only the reflected edits drain, the rest stay`() {
        val reflected = snapshot(
            sets = listOf(WatchSet(235.0, 5, "TOP", done = true), WatchSet(175.0, 8, "BACKOFF", done = false)),
        )
        val done0 = delta(setIndex = 0, done = true) // reflected
        val done1 = delta(setIndex = 1, done = true) // not reflected
        assertEquals(listOf(done1), PendingEdits.reconcile(listOf(done0, done1), reflected))
    }

    // --- superseded-delta drop (PR #63 review finding 1) -----------------------

    @Test
    fun `a delta superseded by a newer same-row edit drains with it`() {
        // w=200 then w=210 to the same row; the snapshot reflects only 210 — the
        // older edit can never settle by reflection and must not re-send forever.
        val older = delta(setIndex = 0, weightLb = 200.0, editedAtMillis = 1L)
        val newer = delta(setIndex = 0, weightLb = 210.0, editedAtMillis = 2L)
        val reflected = snapshot(
            sets = listOf(WatchSet(210.0, 5, "TOP", done = false), WatchSet(175.0, 8, "BACKOFF", done = false)),
        )
        assertTrue(PendingEdits.reconcile(listOf(older, newer), reflected).isEmpty())
    }

    @Test
    fun `an unreflected superseded delta drops even before its successor settles`() {
        val older = delta(setIndex = 0, weightLb = 200.0, editedAtMillis = 1L)
        val newer = delta(setIndex = 0, weightLb = 210.0, editedAtMillis = 2L)
        // Snapshot reflects neither yet — the newer stays, the superseded one goes.
        assertEquals(listOf(newer), PendingEdits.reconcile(listOf(older, newer), snapshot()))
    }

    @Test
    fun `a newer edit to different fields does not supersede a pending weight edit`() {
        val weightEdit = delta(setIndex = 0, weightLb = 200.0, editedAtMillis = 1L)
        val repsEdit = delta(setIndex = 0, reps = 6, editedAtMillis = 2L)
        // Field-aware: the reps-only edit doesn't carry the weight, so the weight
        // edit must survive until the snapshot reflects it.
        assertEquals(listOf(weightEdit, repsEdit), PendingEdits.reconcile(listOf(weightEdit, repsEdit), snapshot()))
    }

    // --- monotonic stamp issue (PR #63 review finding 2) -----------------------

    @Test
    fun `two edits in the same wall-clock millisecond get strictly increasing stamps`() {
        val first = PendingEdits.nextStamp(nowMillis = 1_000L, lastIssuedMillis = 0L)
        val second = PendingEdits.nextStamp(nowMillis = 1_000L, lastIssuedMillis = first)
        assertEquals(1_000L, first)
        assertTrue(second > first, "stamps must strictly increase: $second <= $first")
        assertEquals(1_001L, second)
    }

    @Test
    fun `a clock that goes backwards still issues a strictly newer stamp`() {
        assertEquals(1_001L, PendingEdits.nextStamp(nowMillis = 900L, lastIssuedMillis = 1_000L))
    }
}
