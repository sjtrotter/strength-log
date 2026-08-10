package cloud.trotter.log.strength.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
    private val entropy: () -> Long = { java.security.SecureRandom().nextLong() },
) : AppliedEditMarkers {

    /**
     * Atomically increments the revision and returns it with its epoch. One
     * [DataStore.edit] for both, so concurrent publishes can neither hand out the same
     * number nor publish a revision from one generation under another's epoch.
     */
    suspend fun nextStamp(): SnapshotStamp {
        var stamp = SnapshotStamp(epoch = 0L, revision = 0L)
        dataStore.edit { prefs ->
            // Millis alone could collide across a same-instant wipe-and-recreate
            // (or read 0 from a broken clock); ten entropy bits under a
            // second-granularity timestamp keep epochs unique while the cold
            // cache's (epoch, revision) ordering stays wall-clock honest.
            val epoch = prefs[EPOCH] ?: mintEpoch().also { prefs[EPOCH] = it }
            val revision = (prefs[REVISION] ?: 0L) + 1
            prefs[REVISION] = revision
            stamp = SnapshotStamp(epoch = epoch, revision = revision)
        }
        return stamp
    }

    private fun mintEpoch(): Long {
        val seconds = (nowMillis() / 1000L).coerceAtLeast(1L)
        val salt = entropy() and 0x3FF
        return (seconds shl 10) or salt
    }

    override suspend fun lastApplied(rowKey: String): Long =
        dataStore.data.first()[appliedKey(rowKey)] ?: 0L

    override suspend fun markApplied(rowKey: String, editedAtMillis: Long) {
        dataStore.edit { it[appliedKey(rowKey)] = editedAtMillis }
    }

    override fun lastAppliedFlow(rowKey: String): Flow<Long> =
        dataStore.data.map { it[appliedKey(rowKey)] ?: 0L }.distinctUntilChanged()

    private fun appliedKey(rowKey: String) = longPreferencesKey("$APPLIED_PREFIX$rowKey")

    private companion object {
        val REVISION = longPreferencesKey("snapshot_revision")
        val EPOCH = longPreferencesKey("snapshot_epoch")
        const val APPLIED_PREFIX = "applied:"
    }
}

/** One publish's place in the sequence: which generation, and where in it. */
data class SnapshotStamp(val epoch: Long, val revision: Long)
