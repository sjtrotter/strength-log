package cloud.trotter.log.strength.rest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cloud.trotter.log.strength.StrengthLogApp
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.standards.PhoneRestTimer
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Screen and notification controls share persistence, alarm and notification updates. */
object PhoneRestActions {
    private val lock = Mutex()

    internal suspend fun <T> locked(block: suspend () -> T): T = lock.withLock { block() }

    suspend fun adjust(settings: SettingsStore, runtime: RestRuntime, now: Long, seconds: Int) = locked {
        val current = settings.phoneRestFlow.first() ?: return@locked
        val adjusted = PhoneRestTimer.adjust(current, now, seconds)
        settings.setPhoneRest(adjusted)
        if (adjusted == null) runtime.cancel() else runtime.arm(adjusted)
    }

    suspend fun skip(settings: SettingsStore, runtime: RestRuntime) = locked {
        skipLocked(settings, runtime)
    }

    internal suspend fun skipLocked(settings: SettingsStore, runtime: RestRuntime) {
        settings.setPhoneRest(null)
        runtime.cancel()
    }
}

class RestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ADD && action != SKIP) return
        val pending = goAsync()
        val settings = EntryPointAccessors.fromApplication(context, StrengthLogApp.SettingsEntryPoint::class.java).settingsStore()
        val runtime = EntryPointAccessors.fromApplication(context, StrengthLogApp.RestRuntimeEntryPoint::class.java).phoneRestRuntime()
        val scope = EntryPointAccessors.fromApplication(context, StrengthLogApp.AppScopeEntryPoint::class.java).appScope()
        scope.launch {
            try {
                when (action) {
                    ADD -> PhoneRestActions.adjust(settings, runtime, System.currentTimeMillis(), 15)
                    SKIP -> PhoneRestActions.skip(settings, runtime)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ADD = "cloud.trotter.log.strength.rest.ADD"
        const val SKIP = "cloud.trotter.log.strength.rest.SKIP"
    }
}
