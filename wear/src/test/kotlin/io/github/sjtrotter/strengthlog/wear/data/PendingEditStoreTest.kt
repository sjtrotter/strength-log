package io.github.sjtrotter.strengthlog.wear.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchDay
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
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
    private val store = PendingEditStore(
        PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("pending-edits", ".preferences_pb").also { it.delete() }
        },
    )

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
                ),
            ),
        ),
        unit = "lb",
    )

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
    fun `issueStamp is strictly monotonic through the persisted marker`() = runTest {
        assertEquals(1_000L, store.issueStamp(1_000L))
        assertEquals(1_001L, store.issueStamp(1_000L)) // same wall-clock ms
        assertEquals(1_002L, store.issueStamp(900L)) // clock went backwards
    }
}
