package cloud.trotter.log.strength.wear.data

import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watch's outbound-queue drain rule (§11.4). A queued edit is re-sent until a
 * snapshot reflects it (that snapshot is the only ack), and dropped once it is —
 * or once the phone has moved to a different suggested day.
 */
class PendingEditsTest {

    private fun swap(
        dayId: String = "A",
        programExerciseId: Long = 1L,
        exerciseName: String = "Front Squat",
        stamp: Long = 1L,
    ) = ExerciseSwapDelta(
        dayId = dayId,
        programExerciseId = programExerciseId,
        exerciseId = "front_squat",
        exerciseName = exerciseName,
        editedAtMillis = stamp,
    )

    private fun snapshot(
        dayId: String = "A",
        sets: List<WatchSet> = listOf(
            WatchSet(235.0, 5, "TOP", done = false),
            WatchSet(175.0, 8, "BACKOFF", done = false),
        ),
        ssSets: List<WatchSet> = emptyList(),
        name: String = "Squat",
        exerciseId: String = "bb_back_squat",
        // The slot's live prescription. A pending swap whose target has fallen out of
        // it is one the phone can only refuse, so the default has to carry the one
        // `swap()` asks for or every swap case here would settle as drift.
        alternates: List<WatchAlternate> = listOf(WatchAlternate("front_squat", "Front Squat")),
    ) = WatchSnapshot(
        revision = 1L,
        suggestedDayId = dayId,
        day = WatchDay(
            dayId = dayId,
            title = "Day",
            accentIndex = 0,
            exercises = listOf(
                WatchExercise(
                    1L, "main", name, 235.0, false,
                    if (ssSets.isEmpty()) null else "Partner", sets, ssSets,
                    alternates = alternates,
                    exerciseId = exerciseId,
                ),
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
        startedAtMillis: Long? = null,
        completedAtMillis: Long? = null,
    ) = SetEditDelta(
        dayId = dayId,
        programExerciseId = 1L,
        slot = slot,
        setIndex = setIndex,
        weightLb = weightLb,
        reps = reps,
        done = done,
        editedAtMillis = editedAtMillis,
        startedAtMillis = startedAtMillis,
        completedAtMillis = completedAtMillis,
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

    // --- per-field settling of multi-field deltas (#85) -------------------------

    @Test
    fun `a multi-field delta stays queued until every field it carries is reflected`() {
        // The dial's tick: done plus the round's start/complete facts, one delta.
        val tick = delta(setIndex = 0, done = true, startedAtMillis = 1_000L, completedAtMillis = 1_045L)
        // Nothing reflected yet.
        assertEquals(listOf(tick), PendingEdits.reconcile(listOf(tick), snapshot()))
        // The tick landed: the snapshot shows done, which is the ack for the whole
        // delta — the phone wrote the stamps in the same transaction.
        val reflected = snapshot(
            sets = listOf(WatchSet(235.0, 5, "TOP", done = true), WatchSet(175.0, 8, "BACKOFF", done = false)),
        )
        assertTrue(PendingEdits.reconcile(listOf(tick), reflected).isEmpty())
    }

    @Test
    fun `a delta carrying weight and done needs both reflected, not either`() {
        val both = delta(setIndex = 0, weightLb = 245.0, done = true)
        // done landed, weight didn't → still queued (the old whole-delta check
        // happened to agree here; the next case is where it didn't).
        val doneOnly = snapshot(
            sets = listOf(WatchSet(235.0, 5, "TOP", done = true), WatchSet(175.0, 8, "BACKOFF", done = false)),
        )
        assertEquals(listOf(both), PendingEdits.reconcile(listOf(both), doneOnly))
        // Weight landed, done didn't → still queued.
        val weightOnly = snapshot(
            sets = listOf(WatchSet(245.0, 5, "TOP", done = false), WatchSet(175.0, 8, "BACKOFF", done = false)),
        )
        assertEquals(listOf(both), PendingEdits.reconcile(listOf(both), weightOnly))
        // Both landed → drains.
        val all = snapshot(
            sets = listOf(WatchSet(245.0, 5, "TOP", done = true), WatchSet(175.0, 8, "BACKOFF", done = false)),
        )
        assertTrue(PendingEdits.reconcile(listOf(both), all).isEmpty())
    }

    @Test
    fun `a multi-field delta settles per field - one reflected, one overwritten`() {
        // The case the old whole-delta check got wrong: the weight of the older
        // multi-field edit can never be reflected (a newer pending edit overwrote
        // it), while its reps did land. Per-field, both are accounted for.
        val older = delta(setIndex = 0, weightLb = 200.0, reps = 5, editedAtMillis = 1L)
        val newer = delta(setIndex = 0, weightLb = 210.0, editedAtMillis = 2L)
        val reflected = snapshot(
            sets = listOf(WatchSet(210.0, 5, "TOP", done = false), WatchSet(175.0, 8, "BACKOFF", done = false)),
        )
        assertTrue(PendingEdits.reconcile(listOf(older, newer), reflected).isEmpty())
    }

    @Test
    fun `a tick undone by a newer pending tick drains instead of re-sending forever`() {
        // Tick, then long-press undo, both queued; the snapshot has caught up with
        // the tick only. The undo carries no stamps, so the tick drops on per-field
        // accounting alone — its `done` is reflected and the stamps ride with it —
        // while the unconfirmed undo keeps re-sending.
        val tick = delta(setIndex = 0, done = true, editedAtMillis = 1L, startedAtMillis = 1_000L, completedAtMillis = 1_045L)
        val undo = delta(setIndex = 0, done = false, editedAtMillis = 2L)
        val tickLanded = snapshot(
            sets = listOf(WatchSet(235.0, 5, "TOP", done = true), WatchSet(175.0, 8, "BACKOFF", done = false)),
        )
        assertEquals(listOf(undo), PendingEdits.reconcile(listOf(tick, undo), tickLanded))

        // Re-ticked after an undo, nothing confirmed yet: the newer tick carries the
        // older one's only visible field, so the older could never be acked (the
        // phone drops it as stale) — it must not re-send forever.
        val reTick = delta(setIndex = 0, done = true, editedAtMillis = 3L, startedAtMillis = 2_000L, completedAtMillis = 2_040L)
        assertEquals(listOf(reTick), PendingEdits.reconcile(listOf(tick, undo, reTick), snapshot()))
    }

    @Test
    fun `a newer tick does not swallow a pending reps edit to the same row`() {
        val repsEdit = delta(setIndex = 0, reps = 6, editedAtMillis = 1L)
        val tick = delta(setIndex = 0, done = true, editedAtMillis = 2L, startedAtMillis = 1_000L, completedAtMillis = 1_045L)
        assertEquals(listOf(repsEdit, tick), PendingEdits.reconcile(listOf(repsEdit, tick), snapshot()))
    }

    @Test
    fun `an edit to a row the snapshot does not carry stays queued`() {
        // Index past the end of the published track: nothing can be confirmed, so
        // the edit must not settle by accident.
        val edit = delta(setIndex = 5, done = true, startedAtMillis = 1_000L, completedAtMillis = 1_045L)
        assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit), snapshot()))
    }

    @Test
    fun `a swap stays queued until its slot becomes the exercise it asked for`() {
        val pending = listOf(swap())
        assertEquals(pending, PendingEdits.reconcileSwaps(pending, snapshot()))

        val landed = snapshot(name = "Front Squat", exerciseId = "front_squat", alternates = emptyList())
        assertTrue(PendingEdits.reconcileSwaps(pending, landed).isEmpty())
    }

    /**
     * The reason settlement keys on the slot's id and not its label. Two catalog
     * entries are allowed to share a display name — two custom exercises, or an
     * alternate named after the lift it replaces. Settling on the name would read
     * this *unchanged* snapshot as the phone having answered, drop the request, and
     * leave the lifter with the lift they asked to be rid of and nothing in flight
     * to fix it.
     */
    @Test
    fun `a swap is not settled by a namesake still sitting in the slot`() {
        val pending = listOf(swap(exerciseName = "Squat"))
        val namesake = snapshot(
            name = "Squat",
            exerciseId = "bb_back_squat",
            alternates = listOf(WatchAlternate("front_squat", "Squat")),
        )

        assertEquals(pending, PendingEdits.reconcileSwaps(pending, namesake))
    }

    /** A publisher too old to send slot ids leaves the watch only the name to go on;
     *  that degradation is deliberate and has to keep working. */
    @Test
    fun `an id-less snapshot falls back to matching the name`() {
        val pending = listOf(swap())
        val landed = snapshot(name = "Front Squat", exerciseId = "", alternates = emptyList())

        assertTrue(PendingEdits.reconcileSwaps(pending, landed).isEmpty())
    }

    /**
     * Refused by drift. The snapshot is the authority document: its alternates are
     * what the phone would accept for that slot right now. A target that has dropped
     * out of the list — a deleted custom exercise, a narrowed equipment set — can only
     * ever be answered INVALID, so re-sending it forever would hold the lift read-only
     * on the wrist waiting for an answer that is never coming.
     */
    @Test
    fun `a swap the phone can no longer accept is abandoned instead of resent forever`() {
        val pending = listOf(swap())
        val drifted = snapshot(alternates = listOf(WatchAlternate("goblet_squat", "Goblet Squat")))

        assertTrue(PendingEdits.reconcileSwaps(pending, drifted).isEmpty())
        // Same for a slot with no prescription at all — nothing can be accepted.
        assertTrue(PendingEdits.reconcileSwaps(pending, snapshot(alternates = emptyList())).isEmpty())
    }

    @Test
    fun `a swap for a day the phone has moved past is abandoned`() {
        assertTrue(PendingEdits.reconcileSwaps(listOf(swap(dayId = "A")), snapshot(dayId = "B")).isEmpty())
    }

    @Test
    fun `a newer swap for the same slot supersedes the older one`() {
        val older = swap(exerciseName = "Front Squat", stamp = 1L)
        val newer = swap(exerciseName = "Goblet Squat", stamp = 2L)
        assertEquals(listOf(newer), PendingEdits.reconcileSwaps(listOf(older, newer), snapshot()))
    }

    @Test
    fun `a swap for a slot the snapshot does not carry stays queued`() {
        val pending = listOf(swap(programExerciseId = 99L))
        assertEquals(pending, PendingEdits.reconcileSwaps(pending, snapshot()))
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
