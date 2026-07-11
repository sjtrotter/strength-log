package io.github.sjtrotter.strengthlog

import android.app.Application
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.sync.WearSyncPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hilt's application root. The object graph is defined in [io.github.sjtrotter.strengthlog.di]. */
@HiltAndroidApp
class StrengthLogApp : Application() {

    /**
     * The wear-sync publisher observes state for the whole process lifetime (D6,
     * m5-wear.md #20) — an app-scope observer, not a foreground service. Started
     * here from the singleton graph; it stays a cheap idle observer on devices with
     * no watch paired (Data Layer writes just go unheard).
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WearSyncEntryPoint {
        fun wearSyncPublisher(): WearSyncPublisher
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RepositoryEntryPoint {
        fun trackerRepository(): TrackerRepository
    }

    /** App-scope background jobs (the one-shot startup fixup). Not cancelled — it
     *  lives as long as the process; a [SupervisorJob] keeps one failure from
     *  tearing down the others. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        EntryPointAccessors.fromApplication(this, WearSyncEntryPoint::class.java)
            .wearSyncPublisher()
            .start()

        // One-shot on first launch of the tracking-types build: reinterpret the
        // reps a user logged for now-TIMED holds (plank, ...) as seconds. Guarded
        // by a DataStore flag, so this is a cheap no-op on every later launch.
        // runCatching (Fable P3 advisory #1): this runs unattended at startup
        // with nothing downstream to surface a failure to, so a corrupt row (a
        // stored `setsJson` that fails to decode) must not crash-loop the app
        // on every launch — log it and move on; the fixup's own idempotency
        // (SettingsStore.md) means a future, fixed build can still pick it up.
        val repository = EntryPointAccessors
            .fromApplication(this, RepositoryEntryPoint::class.java)
            .trackerRepository()
        appScope.launch {
            runCatching { repository.runLegacyTimedFixupIfNeeded() }
                .onFailure { Log.e(TAG, "Legacy TIMED fixup failed; will retry next launch", it) }
        }
    }

    private companion object {
        const val TAG = "StrengthLogApp"
    }
}
