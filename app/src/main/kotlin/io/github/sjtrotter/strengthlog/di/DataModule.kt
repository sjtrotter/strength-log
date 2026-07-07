package io.github.sjtrotter.strengthlog.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.sjtrotter.strengthlog.data.AppData
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import javax.inject.Singleton

/**
 * Wires the data layer into the object graph. [AppData] stays the single place
 * that builds Room + DataStore + the repository (SSOT); Hilt just scopes the
 * result to the process as a singleton.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun trackerRepository(@ApplicationContext context: Context): TrackerRepository =
        AppData.repository(context)
}
