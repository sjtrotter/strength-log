package cloud.trotter.log.strength.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.sync.SetEditApplier
import cloud.trotter.log.strength.sync.CardioDeltaApplier
import cloud.trotter.log.strength.sync.CardioAckLog
import cloud.trotter.log.strength.sync.EditAckLog
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.sync.TodaySnapshotSource
import cloud.trotter.log.strength.sync.WearSyncPublisher
import cloud.trotter.log.strength.sync.WearSyncStore
import cloud.trotter.log.strength.sync.WatchContinuitySource
import java.time.LocalDate
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

/**
 * Object graph for the phone-side wear sync (D6), plus the [TodaySnapshotSource]
 * the publisher shares with the home-screen widget ([WidgetModule]). Kept separate
 * from [DataModule] (the training-data graph) because this is transport wiring; the
 * sync classes are plain constructors (framework-free, like `:data`/`:transfer`)
 * assembled here.
 */
@Module
@InstallIn(SingletonComponent::class)
object WearSyncModule {

    private val Context.wearSyncDataStore: DataStore<Preferences> by preferencesDataStore(name = "wear_sync")

    @Provides
    @Singleton
    fun wearSyncStore(@ApplicationContext context: Context): WearSyncStore =
        WearSyncStore(context.wearSyncDataStore)

    @Provides
    fun watchContinuitySource(store: WearSyncStore): WatchContinuitySource = store

    @Provides
    fun cardioAckLog(store: WearSyncStore): CardioAckLog = store

    @Provides
    fun editAckLog(store: WearSyncStore): EditAckLog = store

    @Provides
    @Singleton
    fun dataClient(@ApplicationContext context: Context): DataClient =
        Wearable.getDataClient(context)

    @Provides
    @Singleton
    fun setEditApplier(
        repo: TrackerRepository,
        store: WearSyncStore,
    ): SetEditApplier = SetEditApplier(repo, store, store)

    @Provides
    @Singleton
    fun cardioDeltaApplier(
        repo: TrackerRepository,
        ackLog: CardioAckLog,
        publisher: SessionPublisher,
    ): CardioDeltaApplier = CardioDeltaApplier(repo, ackLog, publisher)

    /** One source, every glance surface: the widget observer reads this same
     *  instance (glance-surfaces.md §4.2). */
    @Provides
    @Singleton
    fun todaySnapshotSource(
        repo: TrackerRepository,
        @CivilDay today: Flow<LocalDate>,
        ackLog: CardioAckLog,
        editAckLog: EditAckLog,
    ): TodaySnapshotSource = TodaySnapshotSource(repo, today, ackLog, editAckLog)

    @Provides
    @Singleton
    fun wearSyncPublisher(
        source: TodaySnapshotSource,
        store: WearSyncStore,
        dataClient: DataClient,
    ): WearSyncPublisher =
        WearSyncPublisher(
            source = source,
            store = store,
            dataClient = dataClient,
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
}
