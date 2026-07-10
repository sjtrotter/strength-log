package io.github.sjtrotter.strengthlog.wear.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.SyncCodec
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
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

    /** Live queue depth — the wire behind [WatchTrackerClient.pendingCountFlow]. */
    fun countFlow(): Flow<Int> =
        dataStore.data.map { prefs -> SyncCodec.decodeDeltaQueue(prefs[QUEUE].orEmpty()).size }

    suspend fun enqueue(delta: SetEditDelta) {
        dataStore.edit { prefs ->
            val current = SyncCodec.decodeDeltaQueue(prefs[QUEUE].orEmpty())
            prefs[QUEUE] = SyncCodec.encodeDeltaQueue(current + delta)
        }
    }

    /** Atomically drops every queued delta [PendingEdits.reconcile] settles
     *  against [snapshot]; deltas enqueued concurrently are preserved (see the
     *  class doc's concurrency note). */
    suspend fun reconcileAgainst(snapshot: WatchSnapshot) {
        dataStore.edit { prefs ->
            val pending = SyncCodec.decodeDeltaQueue(prefs[QUEUE].orEmpty())
            val stillPending = PendingEdits.reconcile(pending, snapshot)
            if (stillPending.size != pending.size) {
                prefs[QUEUE] = SyncCodec.encodeDeltaQueue(stillPending)
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
        val LAST_ISSUED = longPreferencesKey("last_issued_stamp")
    }
}
