package cloud.trotter.log.strength.wear.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The durable queue over a real (plain-JVM) Preferences DataStore. The
 * load-bearing property is that [PendingEditStore.reconcileAgainst] is one atomic
 * `DataStore.edit` — a concurrent enqueue can't land between its read and its
 * write and be wiped. True concurrency isn't schedulable in a unit test; this
 * pins the functional halves (settled deltas drop, unsettled and later enqueues
 * survive) while the atomicity itself is DataStore's serialized-edit guarantee,
 * documented on the store.
 */
class PendingEditStoreTest {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        File.createTempFile("pending-edits", ".preferences_pb").also { it.delete() }
    }
    private val store = PendingEditStore(dataStore)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun delta(setIndex: Int, done: Boolean? = null, reps: Int? = null, stamp: Long) = SetEditDelta(
        dayId = "A",
        programExerciseId = 1L,
        slot = "main",
        setIndex = setIndex,
        reps = reps,
        done = done,
        editedAtMillis = stamp,
    )

    private fun swap(
        programExerciseId: Long = 1L,
        exerciseId: String = "front_squat",
        exerciseName: String = "Front Squat",
        stamp: Long = 1L,
    ) = ExerciseSwapDelta(
        dayId = "A",
        programExerciseId = programExerciseId,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        editedAtMillis = stamp,
    )

    private val snapshot = WatchSnapshot(
        revision = 5L,
        suggestedDayId = "A",
        day = WatchDay(
            dayId = "A",
            title = "Day",
            accentIndex = 0,
            exercises = listOf(
                WatchExercise(
                    programExerciseId = 1L,
                    slot = "main",
                    name = "Squat",
                    goal = 235.0,
                    perHand = false,
                    supersetPartnerName = null,
                    sets = listOf(WatchSet(235.0, 5, "TOP", done = true), WatchSet(175.0, 8, "BACKOFF", done = false)),
                    ssSets = emptyList(),
                    // Slot 1 is still the squat, and still prescribes the swap the
                    // tests queue against it — so a pending swap here settles only by
                    // landing, never as drift.
                    alternates = listOf(WatchAlternate("front_squat", "Front Squat")),
                    exerciseId = "bb_back_squat",
                ),
            ),
        ),
        unit = "lb",
    )

    @Test
    fun `the queue can say which lifts have an edit still in flight`() = runTest {
        assertEquals(emptySet(), store.exerciseIdsFlow().first())

        store.enqueue(delta(setIndex = 0, done = true, stamp = 1L))
        store.enqueue(delta(setIndex = 1, done = true, stamp = 2L))
        // Two edits, one lift — the question is which lifts, not how many edits.
        assertEquals(setOf(1L), store.exerciseIdsFlow().first())
        assertEquals(2, store.countFlow().first())

        store.enqueue(delta(setIndex = 0, done = true, stamp = 3L).copy(programExerciseId = 9L))
        assertEquals(setOf(1L, 9L), store.exerciseIdsFlow().first())

        // Settling row 0 of lift 1 leaves lift 1 in flight on row 1's account.
        store.reconcileAgainst(snapshot)
        assertEquals(setOf(1L, 9L), store.exerciseIdsFlow().first())
    }

    @Test
    fun `reconcileAgainst drops settled deltas and preserves the rest`() = runTest {
        val settled = delta(setIndex = 0, done = true, stamp = 1L) // snapshot shows row 0 done
        val pending = delta(setIndex = 1, done = true, stamp = 2L) // row 1 still un-done
        store.enqueue(settled)
        store.enqueue(pending)

        store.reconcileAgainst(snapshot)
        assertEquals(listOf(pending), store.all())

        // An edit enqueued after a reconcile is untouched by it.
        val later = delta(setIndex = 1, reps = 9, stamp = 3L)
        store.enqueue(later)
        assertEquals(listOf(pending, later), store.all())
    }

    @Test
    fun `countFlow reflects the live queue depth`() = runTest {
        assertEquals(0, store.countFlow().first())
        store.enqueue(delta(setIndex = 0, done = true, stamp = 1L))
        assertEquals(1, store.countFlow().first())
        store.enqueue(delta(setIndex = 1, done = true, stamp = 2L))
        assertEquals(2, store.countFlow().first())
        store.reconcileAgainst(snapshot) // settles setIndex 0 (already done in the snapshot)
        assertEquals(1, store.countFlow().first())
    }

    @Test
    fun `the swap queue persists enqueued swaps`() = runTest {
        val queued = swap()
        store.enqueueSwap(queued)
        assertEquals(listOf(queued), store.allSwaps())
    }

    @Test
    fun `countFlow includes set edits and swaps`() = runTest {
        store.enqueue(delta(setIndex = 1, done = true, stamp = 1L))
        store.enqueueSwap(swap(stamp = 2L))
        assertEquals(2, store.countFlow().first())
    }

    @Test
    fun `exercise ids include a lift with only a swap in flight`() = runTest {
        store.enqueueSwap(swap(programExerciseId = 9L))
        assertEquals(setOf(9L), store.exerciseIdsFlow().first())
    }

    @Test
    fun `swap exercise ids exclude lifts with only set edits`() = runTest {
        store.enqueue(delta(setIndex = 1, done = true, stamp = 1L))
        store.enqueueSwap(swap(programExerciseId = 9L, stamp = 2L))
        assertEquals(setOf(9L), store.swapExerciseIdsFlow().first())
    }

    @Test
    fun `reconcileAgainst settles both queues and preserves both pending tails`() = runTest {
        val settledEdit = delta(setIndex = 0, done = true, stamp = 1L)
        val pendingEdit = delta(setIndex = 1, done = true, stamp = 2L)
        // Settles by landing: slot 1 already *is* bb_back_squat in the snapshot.
        val settledSwap = swap(exerciseId = "bb_back_squat", exerciseName = "Squat", stamp = 3L)
        val pendingSwap = swap(programExerciseId = 9L, stamp = 4L)
        store.enqueue(settledEdit)
        store.enqueue(pendingEdit)
        store.enqueueSwap(settledSwap)
        store.enqueueSwap(pendingSwap)

        store.reconcileAgainst(snapshot)
        assertEquals(listOf(pendingEdit), store.all())
        assertEquals(listOf(pendingSwap), store.allSwaps())
    }

    @Test
    fun `issueStamp is strictly monotonic through the persisted marker`() = runTest {
        assertEquals(1_000L, store.issueStamp(1_000L))
        assertEquals(1_001L, store.issueStamp(1_000L)) // same wall-clock ms
        assertEquals(1_002L, store.issueStamp(900L)) // clock went backwards
    }

    /** [F] */
    @Test
    fun `delivery of a replaced exercise is persisted before snapshot settlement`() = runTest {
        val edit = delta(setIndex = 1, done = true, stamp = 10)
            .copy(planId = 1, runId = "r-1", exerciseId = "bb_back_squat")
        val replaced = snapshot.copy(planId = 1, runId = "r-1", day = snapshot.day.copy(
            exercises = snapshot.day.exercises.map { it.copy(exerciseId = "hack_squat") },
        ))
        store.enqueue(edit)
        store.reconcileAgainst(replaced)
        assertEquals(listOf(edit), store.all())
        store.markDelivered(edit)
        val restored = PendingEditStore(dataStore)
        assertEquals(listOf(edit), restored.all())
        restored.reconcileAgainst(replaced)
        assertEquals(emptyList(), restored.all())
    }

    /** [F] */
    @Test
    fun `delivery after a replaced snapshot settles only that edit`() = runTest {
        val edit = delta(setIndex = 1, done = true, stamp = 10)
            .copy(planId = 1, runId = "r-1", exerciseId = "bb_back_squat")
        val other = delta(setIndex = 0, done = true, stamp = 11).copy(programExerciseId = 9)
        val replaced = snapshot.copy(planId = 1, runId = "r-1", day = snapshot.day.copy(
            exercises = snapshot.day.exercises.map { it.copy(exerciseId = "hack_squat") },
        ))
        store.enqueue(edit)
        store.enqueue(other)
        store.markDelivered(edit, replaced)
        assertEquals(listOf(other), store.all())
    }

    /** [F] */
    @Test
    fun `both run-aware queues survive another day and settle on their own closed run`() = runTest {
        val edit = delta(setIndex = 1, done = true, stamp = 10).copy(planId = 1, runId = "r-a")
        val replacement = swap(stamp = 11).copy(planId = 1, runId = "r-a")
        store.enqueue(edit)
        store.enqueueSwap(replacement)
        store.reconcileAgainst(snapshot.copy(planId = 1, runId = "r-b", day = snapshot.day.copy(dayId = "B")))
        assertEquals(listOf(edit), store.all())
        assertEquals(listOf(replacement), store.allSwaps())
        store.reconcileAgainst(snapshot.copy(planId = 1, runId = "r-next"))
        assertEquals(emptyList(), store.all())
        assertEquals(emptyList(), store.allSwaps())
    }

    /** [F] */
    @Test
    fun `both queues settle hidden-day edits only after persisted delivery in either order`() = runTest {
        val otherDay = snapshot.copy(planId = 1, runId = "r-b", day = snapshot.day.copy(dayId = "B"))
        for (deliveredFirst in listOf(false, true)) {
            val stamp = if (deliveredFirst) 20L else 10L
            val edit = delta(setIndex = 1, done = true, stamp = stamp).copy(planId = 1, runId = "r-a")
            val replacement = swap(stamp = stamp + 1).copy(planId = 1, runId = "r-a")
            store.enqueue(edit)
            store.enqueueSwap(replacement)
            if (deliveredFirst) {
                store.markDelivered(edit)
                store.markDelivered(replacement)
                PendingEditStore(dataStore).reconcileAgainst(otherDay)
            } else {
                store.reconcileAgainst(otherDay)
                assertEquals(listOf(edit), store.all())
                assertEquals(listOf(replacement), store.allSwaps())
                store.markDelivered(edit, otherDay)
                store.markDelivered(replacement, otherDay)
            }
            assertEquals(emptyList(), store.all())
            assertEquals(emptyList(), store.allSwaps())
        }
    }
}
