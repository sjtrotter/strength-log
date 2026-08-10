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
import cloud.trotter.log.strength.sync.TodaySnapshotSource
import cloud.trotter.log.strength.sync.WearSyncPublisher
import cloud.trotter.log.strength.sync.WearSyncStore
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
    @Singleton
    fun dataClient(@ApplicationContext context: Context): DataClient =
        Wearable.getDataClient(context)

    @Provides
    @Singleton
    fun setEditApplier(
        repo: TrackerRepository,
        store: WearSyncStore,
        @CivilDay today: Flow<LocalDate>,
    ): SetEditApplier = SetEditApplier(repo, store, today)

    /** One source, every glance surface: the widget observer reads this same
     *  instance (glance-surfaces.md §4.2). */
    @Provides
    @Singleton
    fun todaySnapshotSource(
        repo: TrackerRepository,
        @CivilDay today: Flow<LocalDate>,
    ): TodaySnapshotSource = TodaySnapshotSource(repo, today)

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
