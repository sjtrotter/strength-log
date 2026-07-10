package io.github.sjtrotter.strengthlog.di

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
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.sync.SetEditApplier
import io.github.sjtrotter.strengthlog.sync.WearSyncPublisher
import io.github.sjtrotter.strengthlog.sync.WearSyncStore
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Object graph for the phone-side wear sync (D6). Kept separate from [DataModule]
 * (the training-data graph) because this is transport wiring; the sync classes are
 * plain constructors (framework-free, like `:data`/`:transfer`) assembled here.
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
    fun setEditApplier(repo: TrackerRepository, store: WearSyncStore): SetEditApplier =
        SetEditApplier(repo, store)

    @Provides
    @Singleton
    fun wearSyncPublisher(
        repo: TrackerRepository,
        store: WearSyncStore,
        dataClient: DataClient,
    ): WearSyncPublisher =
        WearSyncPublisher(
            repo = repo,
            store = store,
            dataClient = dataClient,
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
}
