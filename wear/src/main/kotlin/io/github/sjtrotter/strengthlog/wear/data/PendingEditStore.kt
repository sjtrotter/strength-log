package io.github.sjtrotter.strengthlog.wear.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.SyncCodec
import kotlinx.coroutines.flow.first

/**
 * Durable home for the watch's unacked outbound deltas (§11.4). Persisted as one
 * JSON array in the watch's own Preferences DataStore, so an edit made while the
 * phone app is dead outlives the *watch* app being closed too, and is re-sent when
 * the phone comes back. Reads/writes are whole-list under [DataStore.edit] so a
 * concurrent enqueue and drain can't clobber each other.
 */
class PendingEditStore(private val dataStore: DataStore<Preferences>) {

    suspend fun all(): List<SetEditDelta> =
        SyncCodec.decodeDeltaQueue(dataStore.data.first()[QUEUE].orEmpty())

    suspend fun enqueue(delta: SetEditDelta) {
        dataStore.edit { prefs ->
            val current = SyncCodec.decodeDeltaQueue(prefs[QUEUE].orEmpty())
            prefs[QUEUE] = SyncCodec.encodeDeltaQueue(current + delta)
        }
    }

    suspend fun replace(deltas: List<SetEditDelta>) {
        dataStore.edit { it[QUEUE] = SyncCodec.encodeDeltaQueue(deltas) }
    }

    private companion object {
        val QUEUE = stringPreferencesKey("pending_edits")
    }
}
