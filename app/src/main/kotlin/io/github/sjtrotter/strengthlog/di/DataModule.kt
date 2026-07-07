package io.github.sjtrotter.strengthlog.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import javax.inject.Singleton

/**
 * The data layer's object graph — the one place the Room DB and Preferences
 * DataStore are constructed. Both must be process singletons: a second DataStore
 * on the same file throws, and a second DB handle wastes a connection pool.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    private const val SETTINGS_FILE = "strength_settings"

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): StrengthDatabase =
        StrengthDatabase.build(context)

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(
            PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile(SETTINGS_FILE) },
            ),
        )

    @Provides
    @Singleton
    fun trackerRepository(db: StrengthDatabase, settings: SettingsStore): TrackerRepository =
        TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
}
