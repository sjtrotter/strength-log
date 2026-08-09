package cloud.trotter.log.strength

import android.app.Application
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.di.ApplicationScope
import cloud.trotter.log.strength.sync.WearSyncPublisher
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.widget.TodayWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Hilt's application root. The object graph is defined in [cloud.trotter.log.strength.di]. */
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

    /** The widget's observer rides the same lifetime and the same source as the
     *  publisher above (glance-surfaces.md §4.2) — it just paints RemoteViews
     *  instead of writing a DataItem. It is inert while no widget is placed. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun todayWidgetUpdater(): TodayWidgetUpdater
    }

    /** The backup core, for the startup restore reconciliation below. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BackupEntryPoint {
        fun backupService(): BackupService
    }

    /** The process-lifetime scope from [cloud.trotter.log.strength.di.AppScopeModule]
     *  — the same one an in-flight restore runs on, so startup jobs and screen-
     *  independent work share one lifetime. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppScopeEntryPoint {
        @ApplicationScope
        fun appScope(): CoroutineScope
    }

    override fun onCreate() {
        super.onCreate()
        EntryPointAccessors.fromApplication(this, WearSyncEntryPoint::class.java)
            .wearSyncPublisher()
            .start()
        EntryPointAccessors.fromApplication(this, WidgetEntryPoint::class.java)
            .todayWidgetUpdater()
            .start()

        val appScope = EntryPointAccessors
            .fromApplication(this, AppScopeEntryPoint::class.java)
            .appScope()

        val backupService = EntryPointAccessors
            .fromApplication(this, BackupEntryPoint::class.java)
            .backupService()
        val repository = EntryPointAccessors
            .fromApplication(this, RepositoryEntryPoint::class.java)
            .trackerRepository()

        // Both startup repairs, in order, in one coroutine — genuinely ordered
        // rather than two launches that would race. Reconciliation goes first
        // because it can rewrite settings the fixup and the first screen both
        // read (`wizardComplete` among them: leave it stale and a first-run
        // wizard can open over a restored program). Launched and never awaited,
        // so startup waits for neither.
        //
        // runCatching *per phase* (Fable P3 advisory #1): these run unattended
        // with nothing downstream to surface a failure to, so neither may
        // crash-loop the app — and a failed reconcile must not swallow the fixup
        // behind it. Both are idempotent, so the next launch retries.
        appScope.launch {
            runCatching { backupService.reconcilePendingRestore() }
                .onFailure { Log.e(TAG, "Pending restore not reconciled; will retry next launch", it) }

            // One-shot on first launch of the tracking-types build: reinterpret
            // the reps a user logged for now-TIMED holds (plank, ...) as seconds.
            // Guarded by a DataStore flag, so this is a cheap no-op on every
            // later launch; a corrupt row (a stored `setsJson` that fails to
            // decode) is logged and skipped, and the fixup's own idempotency
            // (SettingsStore.md) means a future, fixed build can still pick it up.
            runCatching { repository.runLegacyTimedFixupIfNeeded() }
                .onFailure { Log.e(TAG, "Legacy TIMED fixup failed; will retry next launch", it) }
        }
    }

    private companion object {
        const val TAG = "StrengthLogApp"
    }
}
