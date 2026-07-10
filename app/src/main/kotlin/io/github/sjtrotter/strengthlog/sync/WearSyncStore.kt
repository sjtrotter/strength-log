package io.github.sjtrotter.strengthlog.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * The phone side's small, durable sync bookkeeping, kept out of the app's main
 * [io.github.sjtrotter.strengthlog.data.prefs.SettingsStore] (this is transport
 * state, not user preference). Its own Preferences DataStore file, a process
 * singleton like every other store (a second handle on one file throws).
 *
 * Holds two things:
 *  - the monotonic snapshot [revision], persisted so a phone-app restart resumes
 *    counting up instead of resetting to 0 — a regressed revision would make a
 *    live watch treat a genuinely newer snapshot as stale;
 *  - the per-slot applied-edit markers ([AppliedEditMarkers]) that dedupe replayed
 *    watch deltas.
 */
class WearSyncStore(private val dataStore: DataStore<Preferences>) : AppliedEditMarkers {

    /**
     * Atomically increments and returns the next revision. The read-modify-write
     * runs inside [DataStore.edit], so concurrent publishes can't hand out the
     * same number.
     */
    suspend fun nextRevision(): Long {
        var next = 0L
        dataStore.edit { prefs ->
            next = (prefs[REVISION] ?: 0L) + 1
            prefs[REVISION] = next
        }
        return next
    }

    override suspend fun lastApplied(slotKey: String): Long =
        dataStore.data.first()[appliedKey(slotKey)] ?: 0L

    override suspend fun markApplied(slotKey: String, editedAtMillis: Long) {
        dataStore.edit { it[appliedKey(slotKey)] = editedAtMillis }
    }

    private fun appliedKey(slotKey: String) = longPreferencesKey("$APPLIED_PREFIX$slotKey")

    private companion object {
        val REVISION = longPreferencesKey("snapshot_revision")
        const val APPLIED_PREFIX = "applied:"
    }
}
