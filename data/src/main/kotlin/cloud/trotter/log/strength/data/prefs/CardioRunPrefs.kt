package cloud.trotter.log.strength.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Each day's cardio run in progress, as JSON the app owns (#627). Its own file, so its frequent writes never
 *  re-emit settings flows. Not carried by a backup; a restore clears it. */
class CardioRunPrefs(private val dataStore: DataStore<Preferences>) {
    private fun key(planId: Long, dayId: String) = stringPreferencesKey("cardio_run:$planId:$dayId")

    suspend fun get(planId: Long, dayId: String): String? = dataStore.data.first()[key(planId, dayId)]

    suspend fun set(planId: Long, dayId: String, json: String?) {
        dataStore.edit { prefs ->
            if (json == null) prefs.remove(key(planId, dayId)) else prefs[key(planId, dayId)] = json
        }
    }

    suspend fun clear() { dataStore.edit { it.clear() } }
}
