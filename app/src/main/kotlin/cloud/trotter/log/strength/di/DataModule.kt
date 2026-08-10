package cloud.trotter.log.strength.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.time.CivilTimeSource
import cloud.trotter.log.strength.time.SystemCivilTimeSource
import java.time.Clock
import java.time.LocalDate
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * The data layer's object graph — the one place the Room DB and Preferences
 * DataStore are constructed. Both must be process singletons: a second DataStore
 * on the same file throws, and a second DB handle wastes a connection pool.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    private const val SETTINGS_FILE = "strength_settings"

    /** The restore journal's own file. Separate from [SETTINGS_FILE] on purpose —
     *  see [RestoreJournal]: a restore clears the settings store wholesale, so a
     *  marker kept there would be erased by the write it guards. */
    private const val RESTORE_JOURNAL_FILE = "restore_journal"

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
    fun restoreJournal(@ApplicationContext context: Context, settings: SettingsStore): RestoreJournal =
        RestoreJournal(
            PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile(RESTORE_JOURNAL_FILE) },
            ),
            settings,
        )

    /** One device clock for the whole process: every write stamp and the daily
     *  checkmark reset read the same wall clock, and tests can substitute a fixed
     *  one. Not for "what day is it *now*" on a screen — this clock's zone is
     *  frozen at construction; that question goes to [CivilTimeSource] (#176). */
    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()

    /** The read-path counterpart to [clock]: a civil day that re-reads the zone
     *  and re-emits when the day turns (#176). */
    @Provides
    @Singleton
    fun civilTimeSource(source: SystemCivilTimeSource): CivilTimeSource = source

    @Provides
    @Singleton
    @CivilDay
    fun civilDay(
        source: CivilTimeSource,
        @ApplicationScope scope: CoroutineScope,
    ): Flow<LocalDate> = source.civilTime
        .map { it.date }
        // Drop replay when the last consumer leaves. A later one must take a
        // fresh CivilTime reading, never briefly observe the date it left behind.
        .shareIn(
            scope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
            replay = 1,
        )

    @Provides
    @Singleton
    fun trackerRepository(db: StrengthDatabase, settings: SettingsStore, clock: Clock): TrackerRepository =
        TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
            clock = clock,
        )
}
