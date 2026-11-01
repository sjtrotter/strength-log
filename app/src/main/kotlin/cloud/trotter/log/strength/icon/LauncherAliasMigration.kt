package cloud.trotter.log.strength.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.launcherAliasStore by preferencesDataStore(name = "launcher_aliases")
private val NORMALIZED = booleanPreferencesKey("normalized")

/**
 * Keep the legacy aliases: pinned home-screen icons point at them. Every alias
 * now shows the barbell, so repair an interrupted old switch once, without ever
 * following the suggested day again. DayA is the manifest's only enabled default.
 */
@Singleton
class LauncherAliasMigration internal constructor(
    private val context: Context,
    private val store: DataStore<Preferences>,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, context.launcherAliasStore)

    private val lock = Mutex()

    suspend fun runOnce() {
        lock.withLock {
            if (store.data.first()[NORMALIZED] == true) return@withLock
            val pm = context.packageManager
            val aliases = ('A'..'G').map { ComponentName(context.packageName, "${context.packageName}.Launcher_Day$it") }
            val selected = aliases.indexOfFirst { alias ->
                when (pm.getComponentEnabledSetting(alias)) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias == aliases.first()
                    else -> false
                }
            }.takeIf { it >= 0 } ?: 0
            // Enable first so interruption cannot leave the app without a launcher.
            pm.setComponentEnabledSetting(aliases[selected], PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            aliases.filterIndexed { index, _ -> index != selected }.forEach { alias ->
                pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            store.edit { it[NORMALIZED] = true }
        }
    }
}
