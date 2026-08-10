package cloud.trotter.log.strength.wear.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Durable home for the watch's unacked outbound deltas (§11.4). Persisted as one
 * JSON array in the watch's own Preferences DataStore, so an edit made while the
 * phone app is dead outlives the *watch* app being closed too, and is re-sent when
 * the phone comes back.
 *
 * Concurrency: every mutation here — [enqueue], [reconcileAgainst], [issueStamp] —
 * is a single [DataStore.edit], and DataStore serializes edits. In particular
 * [reconcileAgainst]'s read-filter-write happens inside one edit, so a concurrent
 * [enqueue] can never land between its read and its write and be wiped from the
 * queue. That atomicity is the whole reason the drop-settled step lives in this
 * class instead of as a read + separate overwrite in the client.
 *
 * Also persists the last-issued `editedAtMillis` stamp ([issueStamp]) — the rule
 * itself is [PendingEdits.nextStamp]; keeping the marker in the same store means a
 * watch process death can't reissue an old stamp, which the phone would then drop
 * as a replay.
 */
class PendingEditStore(private val dataStore: DataStore<Preferences>) {

    suspend fun all(): List<SetEditDelta> =
        SyncCodec.decodeDeltaQueue(dataStore.data.first()[QUEUE].orEmpty())

    suspend fun allSwaps(): List<ExerciseSwapDelta> =
        SyncCodec.decodeSwapQueue(dataStore.data.first()[SWAP_QUEUE].orEmpty())

    suspend fun allCardio(): List<CardioDelta> =
        SyncCodec.decodeCardioQueue(dataStore.data.first()[CARDIO_QUEUE].orEmpty())

    /** The queue itself, live — the one decode the projections below share. */
    fun queueFlow(): Flow<List<SetEditDelta>> =
        dataStore.data.map { prefs -> SyncCodec.decodeDeltaQueue(prefs[QUEUE].orEmpty()) }

    /** The unacked swaps, live — kept in its own array beside [queueFlow] because a
     *  swap is its own message shape ([ExerciseSwapDelta]), not a set edit. */
    fun swapQueueFlow(): Flow<List<ExerciseSwapDelta>> =
        dataStore.data.map { prefs -> SyncCodec.decodeSwapQueue(prefs[SWAP_QUEUE].orEmpty()) }

    /** Live queue depth — the wire behind [WatchTrackerClient.pendingCountFlow].
     *  All queues count: a swap or cardio completion the phone hasn't answered is
     *  exactly the kind of
     *  thing "2 QUEUED" is there to admit to. */
    fun countFlow(): Flow<Int> =
        dataStore.data.map { prefs ->
            SyncCodec.decodeDeltaQueue(prefs[QUEUE].orEmpty()).size +
                SyncCodec.decodeSwapQueue(prefs[SWAP_QUEUE].orEmpty()).size +
                SyncCodec.decodeCardioQueue(prefs[CARDIO_QUEUE].orEmpty()).size
        }

    /** Which exercises have an edit still unacked — the wire behind
     *  [WatchTrackerClient.pendingExercisesFlow]. A pending *swap* counts too: the
     *  question this answers is "is anything of mine still in flight against this
     *  lift", and nothing is more in flight against a lift than asking the phone to
     *  replace it (see [cloud.trotter.log.strength.wear.ui.ExerciseSelection.shouldForget]). */
    fun exerciseIdsFlow(): Flow<Set<Long>> =
        dataStore.data.map { prefs ->
            val ids = SyncCodec.decodeDeltaQueue(prefs[QUEUE].orEmpty())
                .mapTo(mutableSetOf()) { it.programExerciseId }
            SyncCodec.decodeSwapQueue(prefs[SWAP_QUEUE].orEmpty())
                .mapTo(ids) { it.programExerciseId }
        }

    /** Which exercises have a *swap* still unacked — narrower than
     *  [exerciseIdsFlow] on purpose: only a swap makes the dial stop offering a lift
     *  (the prescription it would show belongs to the exercise being replaced), and a
     *  pending tick must never do that. */
    fun swapExerciseIdsFlow(): Flow<Set<Long>> =
        swapQueueFlow().map { swaps -> swaps.mapTo(mutableSetOf()) { it.programExerciseId } }

    suspend fun enqueue(delta: SetEditDelta) {
        dataStore.edit { prefs ->
            val current = SyncCodec.decodeDeltaQueue(prefs[QUEUE].orEmpty())
            prefs[QUEUE] = SyncCodec.encodeDeltaQueue(current + delta)
        }
    }

    suspend fun enqueueSwap(swap: ExerciseSwapDelta) {
        dataStore.edit { prefs ->
            val current = SyncCodec.decodeSwapQueue(prefs[SWAP_QUEUE].orEmpty())
            prefs[SWAP_QUEUE] = SyncCodec.encodeSwapQueue(current + swap)
        }
    }

    suspend fun enqueueCardio(delta: CardioDelta) {
        dataStore.edit { prefs ->
            val current = SyncCodec.decodeCardioQueue(prefs[CARDIO_QUEUE].orEmpty())
            prefs[CARDIO_QUEUE] = SyncCodec.encodeCardioQueue(current + delta)
        }
    }

    /** Atomically drops every queued delta [PendingEdits.reconcile] and every queued
     *  swap [PendingEdits.reconcileSwaps] settles against [snapshot]; entries enqueued
     *  concurrently are preserved (see the class doc's concurrency note). Both queues
     *  reconcile inside the *same* edit, so the pending projections above can never be
     *  read half-settled. */
    suspend fun reconcileAgainst(snapshot: WatchSnapshot) {
        dataStore.edit { prefs ->
            val pending = SyncCodec.decodeDeltaQueue(prefs[QUEUE].orEmpty())
            val stillPending = PendingEdits.reconcile(pending, snapshot)
            if (stillPending.size != pending.size) {
                prefs[QUEUE] = SyncCodec.encodeDeltaQueue(stillPending)
            }
            val swaps = SyncCodec.decodeSwapQueue(prefs[SWAP_QUEUE].orEmpty())
            val stillSwapping = PendingEdits.reconcileSwaps(swaps, snapshot)
            if (stillSwapping.size != swaps.size) {
                prefs[SWAP_QUEUE] = SyncCodec.encodeSwapQueue(stillSwapping)
            }
            val cardio = SyncCodec.decodeCardioQueue(prefs[CARDIO_QUEUE].orEmpty())
            val stillCardio = PendingEdits.reconcileCardio(cardio, snapshot)
            if (stillCardio.size != cardio.size) {
                prefs[CARDIO_QUEUE] = SyncCodec.encodeCardioQueue(stillCardio)
            }
        }
    }

    /** A strictly monotonic `editedAtMillis` for an edit made "now" — the
     *  read-modify-write runs inside one [DataStore.edit], so concurrent issues
     *  can't hand out the same stamp. */
    suspend fun issueStamp(nowMillis: Long): Long {
        var issued = 0L
        dataStore.edit { prefs ->
            issued = PendingEdits.nextStamp(nowMillis, prefs[LAST_ISSUED] ?: 0L)
            prefs[LAST_ISSUED] = issued
        }
        return issued
    }

    private companion object {
        val QUEUE = stringPreferencesKey("pending_edits")
        val SWAP_QUEUE = stringPreferencesKey("pending_swaps")
        val CARDIO_QUEUE = stringPreferencesKey("pending_cardio")
        val LAST_ISSUED = longPreferencesKey("last_issued_stamp")
    }
}
