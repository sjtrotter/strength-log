package cloud.trotter.log.strength.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * The phone side's small, durable sync bookkeeping, kept out of the app's main
 * [cloud.trotter.log.strength.data.prefs.SettingsStore] (this is transport
 * state, not user preference). Its own Preferences DataStore file, a process
 * singleton like every other store (a second handle on one file throws).
 *
 * Holds three things:
 *  - the monotonic snapshot revision, persisted so a phone-app restart resumes
 *    counting up instead of resetting to 0 — a regressed revision would make a
 *    live watch treat a genuinely newer snapshot as stale;
 *  - the [epoch][cloud.trotter.log.strength.domain.sync.WatchSnapshot.epoch] that
 *    revision belongs to, minted the first time this store is asked for a stamp. The
 *    pairing is the whole point: the only thing that can restart the counter is this
 *    file going away (app data cleared, app reinstalled), and that takes the epoch
 *    with it — so a restarted count always arrives under a new epoch, and the watch
 *    can tell "1 because we started over" from "1, which is older than the 500 I
 *    hold";
 *  - the per-row applied-edit markers ([AppliedEditMarkers]) that dedupe replayed
 *    watch deltas.
 */
class WearSyncStore(
    private val dataStore: DataStore<Preferences>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AppliedEditMarkers {

    /**
     * Atomically increments the revision and returns it with its epoch. One
     * [DataStore.edit] for both, so concurrent publishes can neither hand out the same
     * number nor publish a revision from one generation under another's epoch.
     */
    suspend fun nextStamp(): SnapshotStamp {
        var stamp = SnapshotStamp(epoch = 0L, revision = 0L)
        dataStore.edit { prefs ->
            val epoch = prefs[EPOCH] ?: nowMillis().also { prefs[EPOCH] = it }
            val revision = (prefs[REVISION] ?: 0L) + 1
            prefs[REVISION] = revision
            stamp = SnapshotStamp(epoch = epoch, revision = revision)
        }
        return stamp
    }

    override suspend fun lastApplied(rowKey: String): Long =
        dataStore.data.first()[appliedKey(rowKey)] ?: 0L

    override suspend fun markApplied(rowKey: String, editedAtMillis: Long) {
        dataStore.edit { it[appliedKey(rowKey)] = editedAtMillis }
    }

    private fun appliedKey(rowKey: String) = longPreferencesKey("$APPLIED_PREFIX$rowKey")

    private companion object {
        val REVISION = longPreferencesKey("snapshot_revision")
        val EPOCH = longPreferencesKey("snapshot_epoch")
        const val APPLIED_PREFIX = "applied:"
    }
}

/** One publish's place in the sequence: which generation, and where in it. */
data class SnapshotStamp(val epoch: Long, val revision: Long)
