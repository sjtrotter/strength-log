package cloud.trotter.log.strength.rest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cloud.trotter.log.strength.StrengthLogApp
import cloud.trotter.log.strength.data.TrackerRepository
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

    suspend fun adjust(repo: TrackerRepository, runtime: RestRuntime, now: Long, seconds: Int) = locked {
        val current = repo.phoneRestFlow.first() ?: return@locked
        val adjusted = PhoneRestTimer.adjust(current, now, seconds)
        repo.setPhoneRest(adjusted)
        if (adjusted == null) runtime.cancel() else runtime.arm(adjusted)
    }

    suspend fun skip(repo: TrackerRepository, runtime: RestRuntime) = locked {
        skipLocked(repo, runtime)
    }

    internal suspend fun skipLocked(repo: TrackerRepository, runtime: RestRuntime) {
        repo.setPhoneRest(PhoneRestTimer.skip())
        runtime.cancel()
    }
}

class RestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ADD && action != SKIP) return
        val pending = goAsync()
        val repo = EntryPointAccessors.fromApplication(context, StrengthLogApp.RepositoryEntryPoint::class.java).trackerRepository()
        val runtime = EntryPointAccessors.fromApplication(context, StrengthLogApp.RestRuntimeEntryPoint::class.java).phoneRestRuntime()
        val scope = EntryPointAccessors.fromApplication(context, StrengthLogApp.AppScopeEntryPoint::class.java).appScope()
        scope.launch {
            try {
                when (action) {
                    ADD -> PhoneRestActions.adjust(repo, runtime, System.currentTimeMillis(), 15)
                    SKIP -> PhoneRestActions.skip(repo, runtime)
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
