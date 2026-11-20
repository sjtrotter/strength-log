package cloud.trotter.log.strength.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Each day's cardio run in progress, as JSON the app owns (#627). Its own file, so
 * its frequent writes never re-emit settings flows. Not carried by a backup; a
 * restore clears both pending writes and the file under the flush lock.
 */
class CardioRunPrefs(private val dataStore: DataStore<Preferences>) {
    private val lock = Mutex()
    private val pending = LinkedHashMap<Pair<Long, String>, String?>()

    private fun key(planId: Long, dayId: String) = stringPreferencesKey("cardio_run:$planId:$dayId")

    suspend fun get(planId: Long, dayId: String): String? = lock.withLock {
        flushLocked()
        synchronized(pending) {
            val key = planId to dayId
            if (pending.containsKey(key)) return@withLock pending[key]
        }
        dataStore.data.first()[key(planId, dayId)]
    }

    fun enqueue(planId: Long, dayId: String, json: String?) {
        synchronized(pending) {
            val key = planId to dayId
            pending.remove(key)
            pending[key] = json
        }
    }

    suspend fun set(planId: Long, dayId: String, json: String?) {
        enqueue(planId, dayId, json)
        flush()
    }

    suspend fun flush() = lock.withLock { flushLocked() }

    private suspend fun flushLocked() {
        while (true) {
            val (address, json) = synchronized(pending) {
                pending.entries.firstOrNull()?.let { entry ->
                    (entry.key to entry.value).also { pending.remove(entry.key) }
                }
            } ?: return
            try {
                dataStore.edit { prefs ->
                    if (json == null) prefs.remove(key(address.first, address.second))
                    else prefs[key(address.first, address.second)] = json
                }
            } catch (_: IOException) {
                synchronized(pending) {
                    if (!pending.containsKey(address)) pending[address] = json
                }
                return
            }
        }
    }

    suspend fun clear() {
        lock.withLock {
            synchronized(pending) { pending.clear() }
            dataStore.edit { it.clear() }
        }
    }
}
