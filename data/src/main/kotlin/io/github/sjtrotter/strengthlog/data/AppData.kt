package io.github.sjtrotter.strengthlog.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore

/**
 * Plain manual construction of the data layer. No DI framework yet: there is one
 * consumer (the app, wired in M3) and a Hilt module would be ceremony with
 * nothing to earn its keep here. When the app module adds Hilt it can provide
 * these as singletons; the constructors are already injection-friendly.
 */
object AppData {

    fun repository(context: Context): TrackerRepository {
        val appContext = context.applicationContext
        val db = StrengthDatabase.build(appContext)
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile("strength_settings") },
        )
        return TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
    }
}
