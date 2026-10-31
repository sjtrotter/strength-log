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
import cloud.trotter.log.strength.rest.PhoneRestRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Hilt's application root. The object graph is defined in [cloud.trotter.log.strength.di]. */
@HiltAndroidApp
class StrengthLogApp : Application() {

    /**
     * The wear-sync publisher observes state for the whole process lifetime (D6,
     * docs/briefs/m5-wear.md, "#20 Wearable Data Layer sync") — an app-scope
     * observer, not a foreground service. Started here from the singleton graph; it
     * stays a cheap idle observer on devices with no watch paired (Data Layer
     * writes just go unheard).
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

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RestRuntimeEntryPoint {
        fun phoneRestRuntime(): PhoneRestRuntime
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

        // The startup repairs, in order, in one coroutine — genuinely ordered
        // rather than separate launches that would race. Reconciliation goes first
        // because it can rewrite settings the fixups and the first screen
        // read (`wizardComplete` among them: leave it stale and a first-run
        // wizard can open over a restored program). Launched and never awaited,
        // so startup waits for none of them.
        //
        // runCatching *per phase*: these run unattended with nothing downstream
        // to surface a failure to, so none may crash-loop the app, and a failed
        // reconcile must not swallow the fixups behind it. All three are
        // idempotent, so the next launch retries.
        appScope.launch {
            runCatching { backupService.reconcilePendingRestore() }
                .onFailure { Log.e(TAG, "Pending restore not reconciled; will retry next launch", it) }

            // Reinterprets reps logged for holds that are TIMED (plank, ...) as
            // seconds. A DataStore flag makes it a no-op after the first
            // success. One undecodable row rolls the whole fixup back, and it
            // retries next launch.
            runCatching { repository.runLegacyTimedFixupIfNeeded() }
                .onFailure { Log.e(TAG, "Legacy TIMED fixup failed; will retry next launch", it) }
            runCatching { repository.adoptLegacySessionStartIfNeeded() }
                .onFailure { Log.e(TAG, "Legacy session start adoption failed", it) }
            runCatching { repository.runPlanSettingsFixupIfNeeded() }
                .onFailure { Log.e(TAG, "Plan settings fixup failed; will retry next launch", it) }
            runCatching { repository.ensureSessionNotesMoved() }
                .onFailure { Log.e(TAG, "Session notes move failed; will retry next launch", it) }
        }
    }

    private companion object {
        const val TAG = "StrengthLogApp"
    }
}
