package cloud.trotter.log.strength.wear.data

import cloud.trotter.log.strength.domain.glance.DayProgress
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.sync.applyDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watch's outbound-queue drain rule (§11.4). A queued edit is re-sent until a
 * snapshot reflects it (that snapshot is the only ack), and dropped once it is —
 * or once the phone has moved to a different suggested day.
 */
class PendingEditsTest {

    @Test
    fun `same plan and day settle edits and swaps for a removed slot`() {
        val edit = delta(done = true).copy(planId = 1L, programExerciseId = 11L)
        val replacement = swap(programExerciseId = 11L).copy(planId = 1L)
        val current = snapshot().copy(planId = 1L)
        // Both an empty successor and a recreated day with other slots retire 11.
        listOf(current.copy(day = current.day.copy(exercises = emptyList())), current).forEach { published ->
            assertTrue(PendingEdits.reconcile(listOf(edit), published).isEmpty())
            assertTrue(PendingEdits.reconcileSwaps(listOf(replacement), published).isEmpty())
        }
    }

    @Test
    fun `another plan cannot settle missing-slot edits or swaps even on a different day`() {
        val edit = delta(done = true).copy(planId = 1L, programExerciseId = 11L)
        val replacement = swap(programExerciseId = 11L).copy(planId = 1L)
        listOf("A", "B").forEach { dayId ->
            val otherPlan = snapshot(dayId = dayId).copy(planId = 2L)
            assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit), otherPlan))
            assertEquals(listOf(replacement), PendingEdits.reconcileSwaps(listOf(replacement), otherPlan))
        }
    }

    @Test
    fun `a null plan id on either side keeps missing-slot work until the day changes`() {
        listOf(null to null, null to 1L, 1L to null).forEach { (deltaPlan, snapshotPlan) ->
            val edit = delta(done = true).copy(planId = deltaPlan, programExerciseId = 11L)
            val replacement = swap(programExerciseId = 11L).copy(planId = deltaPlan)
            val current = snapshot().copy(planId = snapshotPlan)
            assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit), current))
            assertEquals(listOf(replacement), PendingEdits.reconcileSwaps(listOf(replacement), current))

            val nextDay = snapshot(dayId = "B").copy(planId = snapshotPlan)
            assertTrue(PendingEdits.reconcile(listOf(edit), nextDay).isEmpty())
            assertTrue(PendingEdits.reconcileSwaps(listOf(replacement), nextDay).isEmpty())
        }
    }

    @Test
    fun `queued work survives another plan's Day A and settles when its own plan reflects it`() {
        val edit = delta(done = true).copy(planId = 1L)
        val replacement = swap().copy(planId = 1L)
        val reflected = snapshot(
            sets = listOf(WatchSet(235.0, 5, "TOP", done = true)),
            exerciseId = "front_squat",
        ).copy(planId = 1L)
        // Q has its own globally unique slot, even when its values match P's work.
        val otherPlan = reflected.copy(
            planId = 2L,
            day = reflected.day.copy(exercises = reflected.day.exercises.map { it.copy(programExerciseId = 2L) }),
        )

        val edits = PendingEdits.reconcile(listOf(edit), otherPlan)
        val swaps = PendingEdits.reconcileSwaps(listOf(replacement), otherPlan)
        assertEquals(listOf(edit), edits)
        assertEquals(listOf(replacement), swaps)
        assertTrue(PendingEdits.reconcile(edits, reflected).isEmpty())
        assertTrue(PendingEdits.reconcileSwaps(swaps, reflected).isEmpty())
    }

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
    fun `out of order cardio acknowledgement keeps a missing earlier completion queued`() {
        val first = CardioDelta(1, "A", "OUTDOOR_RUN", false, "Easy Zone 2", 100L, 60_100L, 60, 0, 100L)
        val second = first.copy(startedAt = 200_000L, completedAt = 260_000L, stamp = 101L)
        val pending = listOf(first, second)
        val laterOnly = snapshot().copy(cardioAckStamp = 101L, cardioAckedStamps = listOf(101L))
        assertEquals(listOf(first), PendingEdits.reconcileCardio(pending, laterOnly))
        assertTrue(PendingEdits.reconcileCardio(pending,
            laterOnly.copy(cardioAckedStamps = listOf(100L, 101L))).isEmpty())
        assertTrue(PendingEdits.reconcileCardio(pending,
            laterOnly.copy(cardioAckedStamps = emptyList())).isEmpty())
    }

    @Test
    fun `cardio survives a plan switch and a processed stamp aged out of the recent list`() {
        val delta = CardioDelta(1, "A", "OUTDOOR_RUN", false, "Easy Zone 2", 100L, 60_100L, 60, 0, 100L,
            planId = 1L)
        val otherPlan = snapshot(dayId = "B").copy(planId = 2L, cardioAckStamp = 140L,
            cardioAckedStamps = (109L..140L).toList())
        assertEquals(listOf(delta), PendingEdits.reconcileCardio(listOf(delta), otherPlan))
        val reacknowledged = otherPlan.copy(cardioAckedStamps = (110L..140L).toList() + delta.stamp)
        assertTrue(PendingEdits.reconcileCardio(listOf(delta), reacknowledged).isEmpty())
    }

    @Test
    fun `cardio stays queued until the phone's ack stamp covers it`() {
        val delta = CardioDelta(1, "A", "OUTDOOR_RUN", false, "Easy Zone 2", 100L, 60_100L, 60, 0, 9L)
        assertEquals(listOf(delta), PendingEdits.reconcileCardio(listOf(delta), snapshot()))
        assertEquals(listOf(delta), PendingEdits.reconcileCardio(listOf(delta), snapshot().copy(cardioAckStamp = 8L)))
        assertTrue(PendingEdits.reconcileCardio(listOf(delta), snapshot().copy(cardioAckStamp = 9L)).isEmpty())
    }

    @Test
    fun `a cardio completion is history and survives the phone moving to another day`() {
        val delta = CardioDelta(1, "A", "OUTDOOR_RUN", false, "Easy Zone 2", 100L, 60_100L, 60, 0, 9L)
        assertEquals(listOf(delta), PendingEdits.reconcileCardio(listOf(delta), snapshot(dayId = "B")))
    }

    @Test
    fun `two same-day completions both settle on one covering ack`() {
        val first = CardioDelta(1, "A", "OUTDOOR_RUN", false, "Easy Zone 2", 100L, 60_100L, 60, 0, 9L)
        val second = CardioDelta(1, "A", "OUTDOOR_RUN", true, "Tempo", 200_000L, 260_000L, 60, 0, 10L)
        assertTrue(PendingEdits.reconcileCardio(listOf(first, second), snapshot().copy(cardioAckStamp = 10L)).isEmpty())
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

    // --- superseded-delta drop -------------------------------------------------

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

    // --- per-field settling of multi-field deltas -------------------------------

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
        // done landed, weight didn't → still queued.
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
        // The weight of the older multi-field edit can never be reflected (a
        // newer pending edit overwrote it), while its reps did land. Per-field,
        // both are accounted for.
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
        val samePlan = edit.copy(planId = 1L)
        assertEquals(listOf(samePlan), PendingEdits.reconcile(listOf(samePlan), snapshot().copy(planId = 1L)))
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

    // --- monotonic stamps ------------------------------------------------------

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

    /** [F] */
    @Test
    fun `another run on the same plan and day settles edits and swaps`() {
        val edit = delta(done = true).copy(planId = 1, runId = "r-1")
        val replacement = swap().copy(planId = 1, runId = "r-1")
        val current = snapshot().copy(planId = 1, runId = "r-2")
        assertTrue(PendingEdits.reconcile(listOf(edit), current).isEmpty())
        assertTrue(PendingEdits.reconcileSwaps(listOf(replacement), current).isEmpty())
    }

    /** [F] */
    @Test
    fun `same run and legacy edits keep per-field settling`() {
        for (runId in listOf("r-1", null)) {
            val edit = delta(weightLb = 235.0, done = true).copy(planId = 1, runId = runId)
            val current = snapshot().copy(planId = 1, runId = "r-1")
            assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit), current))
            val reflected = current.copy(day = current.day.copy(exercises = current.day.exercises.map {
                it.copy(sets = it.sets.map { row -> row.copy(done = true) })
            }))
            assertTrue(PendingEdits.reconcile(listOf(edit), reflected).isEmpty())
        }
    }

    /** [F] */
    @Test
    fun `same run and legacy swaps require their own acknowledgment`() {
        for (runId in listOf("r-1", null)) {
            val replacement = swap().copy(planId = 1, runId = runId)
            val current = snapshot().copy(planId = 1, runId = "r-1")
            assertEquals(listOf(replacement), PendingEdits.reconcileSwaps(listOf(replacement), current))
            val reflected = current.copy(day = current.day.copy(exercises = current.day.exercises.map {
                it.copy(exerciseId = replacement.exerciseId)
            }))
            assertTrue(PendingEdits.reconcileSwaps(listOf(replacement), reflected).isEmpty())
        }
    }

    /** [F] */
    @Test
    fun `another plan leaves another run's edits and swaps queued until the phone has processed them`() {
        val edit = delta(done = true).copy(planId = 1, runId = "r-1")
        val replacement = swap().copy(planId = 1, runId = "r-1")
        val current = snapshot().copy(planId = 2, runId = "r-2")
        assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit), current))
        assertEquals(listOf(replacement), PendingEdits.reconcileSwaps(listOf(replacement), current))
        // Processed means committed or refused for good: the phone is finished with it on any plan.
        assertTrue(PendingEdits.reconcile(listOf(edit), current.copy(processedEditStamps = listOf(edit.editedAtMillis))).isEmpty())
        assertTrue(PendingEdits.reconcileSwaps(listOf(replacement), current.copy(processedEditStamps = listOf(replacement.editedAtMillis))).isEmpty())
    }

    /** [F] */
    @Test
    fun `an undelivered tick for a replaced exercise stays queued without projection or extra progress`() {
        val edit = delta(done = true).copy(planId = 1, runId = "r-1", exerciseId = "bb_back_squat")
        for (replacementDone in listOf(false, true)) {
            val current = snapshot(exerciseId = "hack_squat", sets = listOf(
                WatchSet(100.0, 5, "WORK", done = replacementDone),
            )).copy(planId = 1, runId = "r-1", retiredRoundsDone = 2)
            assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit), current))
            assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit),
                current.copy(processedEditStamps = listOf(edit.editedAtMillis + 1))))
            val displayed = applyDelta(current, edit)
            assertEquals(current, displayed)
            assertEquals(DayProgress(if (replacementDone) 3 else 2, 3), DayProgress.of(displayed))
            assertTrue(PendingEdits.reconcile(listOf(edit), current.copy(processedEditStamps = listOf(edit.editedAtMillis))).isEmpty())
        }
    }

    /** [F] */
    @Test
    fun `a swap back to matching identity uses per-field settling until the phone has processed it`() {
        val edit = delta(weightLb = 245.0, done = true)
            .copy(planId = 1, runId = "r-1", exerciseId = "bb_back_squat")
        val current = snapshot(sets = listOf(WatchSet(235.0, 5, "TOP", done = true)))
            .copy(planId = 1, runId = "r-1")
        assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit), current))
        assertEquals(245.0, applyDelta(current, edit).day.exercises.single().sets.single().weightLb)
        val reflected = current.copy(day = current.day.copy(exercises = current.day.exercises.map {
            it.copy(sets = listOf(WatchSet(245.0, 5, "TOP", done = true)))
        }))
        assertTrue(PendingEdits.reconcile(listOf(edit), reflected).isEmpty())
        // Processed but refused (the phone kept 235): settle anyway, or it would resend forever.
        assertTrue(PendingEdits.reconcile(listOf(edit), current.copy(processedEditStamps = listOf(edit.editedAtMillis))).isEmpty())
    }

    @Test
    fun `a run-less edit is never judged by exercise identity`() {
        val edit = delta(done = true).copy(planId = 1, runId = null, exerciseId = "bb_back_squat")
        val current = snapshot(exerciseId = "hack_squat", sets = listOf(WatchSet(100.0, 5, "WORK", done = true)))
            .copy(planId = 1, runId = null)
        // An old phone applies it to the slot's current lift and publishes no processed stamps.
        assertTrue(PendingEdits.reconcile(listOf(edit), current).isEmpty())
    }

    /** [F] */
    @Test
    fun `a newer edit for another exercise cannot supersede the original after a swap back`() {
        val squat = delta(reps = 6).copy(planId = 1, runId = "r-1", exerciseId = "bb_back_squat")
        val hack = squat.copy(exerciseId = "hack_squat", reps = 8, editedAtMillis = 2)
        val current = snapshot().copy(planId = 1, runId = "r-1")
        assertEquals(listOf(squat, hack), PendingEdits.reconcile(listOf(squat, hack), current))
        assertEquals(listOf(squat), PendingEdits.reconcile(listOf(squat, hack), current.copy(processedEditStamps = listOf(hack.editedAtMillis))))
    }

    /** [F] */
    @Test
    fun `another day preserves run-aware edits until their own run changes`() {
        val edit = delta(done = true).copy(planId = 1, runId = "r-a")
        val anotherDay = snapshot(dayId = "B").copy(planId = 1, runId = "r-b")
        assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit), anotherDay))
        assertTrue(PendingEdits.reconcile(listOf(edit), anotherDay.copy(processedEditStamps = listOf(edit.editedAtMillis))).isEmpty())
        assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit), anotherDay.copy(processedEditStamps = emptyList())))
        assertEquals(anotherDay, applyDelta(anotherDay, edit))
        val ownDay = snapshot().copy(planId = 1, runId = "r-a")
        assertEquals(listOf(edit), PendingEdits.reconcile(listOf(edit), ownDay))
        assertTrue(PendingEdits.reconcile(listOf(edit), ownDay.copy(runId = "r-next")).isEmpty())
        assertTrue(PendingEdits.reconcile(listOf(edit.copy(runId = null)), anotherDay).isEmpty())
    }

    /** [F] */
    @Test
    fun `another day preserves run-aware swaps until their own run changes`() {
        val replacement = swap().copy(planId = 1, runId = "r-a")
        val anotherDay = snapshot(dayId = "B").copy(planId = 1, runId = "r-b")
        assertEquals(listOf(replacement), PendingEdits.reconcileSwaps(listOf(replacement), anotherDay))
        assertTrue(PendingEdits.reconcileSwaps(listOf(replacement), anotherDay.copy(processedEditStamps = listOf(replacement.editedAtMillis))).isEmpty())
        assertEquals(listOf(replacement), PendingEdits.reconcileSwaps(listOf(replacement),
            anotherDay.copy(processedEditStamps = listOf(replacement.editedAtMillis + 1))))
        val ownDay = snapshot().copy(planId = 1, runId = "r-a")
        assertEquals(listOf(replacement), PendingEdits.reconcileSwaps(listOf(replacement), ownDay))
        assertTrue(PendingEdits.reconcileSwaps(listOf(replacement), ownDay.copy(runId = "r-next")).isEmpty())
        assertTrue(PendingEdits.reconcileSwaps(listOf(replacement.copy(runId = null)), anotherDay).isEmpty())
    }
}
