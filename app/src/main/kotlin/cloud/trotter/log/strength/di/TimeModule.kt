package cloud.trotter.log.strength.di

import cloud.trotter.log.strength.time.CivilTimeSource
import cloud.trotter.log.strength.time.SystemCivilTimeSource
import cloud.trotter.log.strength.ui.day.AndroidCardioAlarm
import cloud.trotter.log.strength.ui.day.CardioAlarm
import cloud.trotter.log.strength.ui.day.CardioClock
import cloud.trotter.log.strength.ui.day.SystemCardioClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.LocalDate
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * Every reading of now in the app comes through here; tests replace this module
 * and nothing else to control time.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    @Provides
    @Singleton
    fun cardioClock(clock: SystemCardioClock): CardioClock = clock

    @Provides
    @Singleton
    fun cardioAlarm(alarm: AndroidCardioAlarm): CardioAlarm = alarm

    /** Write stamps and legacy restore dates must use the same clock.
     *  Its zone is frozen at construction; live screen dates require [CivilTimeSource]. */
    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()

    /** The read-path counterpart to [clock]: a civil day that re-reads the zone
     *  and re-emits when the day turns. */
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
}
