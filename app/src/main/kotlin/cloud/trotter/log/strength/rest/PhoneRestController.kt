package cloud.trotter.log.strength.rest

import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.domain.standards.PhoneRestTimer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PhoneRestController @Inject constructor(
    private val settings: SettingsStore,
    private val runtime: RestRuntime,
) {
    private val lock = Mutex()
    private val lockedScope = Locked()
    val state: Flow<PhoneRest?> = settings.phoneRestFlow
    val available: Boolean get() = runtime.available

    suspend fun <T> locked(block: suspend Locked.() -> T): T = lock.withLock { lockedScope.block() }

    inner class Locked internal constructor() {
        suspend fun current(): PhoneRest? = state.first()

        suspend fun start(rest: PhoneRest) {
            settings.setPhoneRest(rest)
            runtime.arm(rest)
        }

        suspend fun skip() {
            settings.setPhoneRest(null)
            runtime.cancel()
        }
    }

    suspend fun adjust(now: Long, seconds: Int) = locked {
        val rest = current() ?: return@locked
        val adjusted = PhoneRestTimer.adjust(rest, now, seconds)
        if (adjusted == null) skip() else start(adjusted)
    }

    suspend fun skip() = locked { skip() }

    /**
     * Foreground expiry keeps the persisted rest for the Rest over grace period,
     * even if its subscriber stops. [expireFromAlarm] clears immediately only in
     * the background so it cannot remove the foreground grace period. Neither
     * clear cancels the Rest over notification that complete() just posted.
     */
    suspend fun expireForeground(deadline: Long, now: () -> Long) {
        val expired = locked {
            val rest = current()
            if (rest == null || rest.deadlineEpochMillis != deadline || deadline > now()) {
                false
            } else {
                runtime.complete()
                true
            }
        }
        if (!expired) return
        withContext(NonCancellable) {
            delay(REST_OVER_MS)
            locked {
                val rest = current()
                if (rest != null && rest.deadlineEpochMillis == deadline && deadline <= now()) {
                    settings.setPhoneRest(null)
                }
            }
        }
    }

    suspend fun expireFromAlarm(now: () -> Long) = locked {
        val rest = current() ?: return@locked
        // An alarm already in flight may predate +15 s or a replacement rest.
        if (rest.deadlineEpochMillis > now()) return@locked
        if (!runtime.isForeground) runtime.arm(rest)
        runtime.complete()
        if (!runtime.isForeground) settings.setPhoneRest(null)
    }

    private companion object {
        const val REST_OVER_MS = 2_000L
    }
}
