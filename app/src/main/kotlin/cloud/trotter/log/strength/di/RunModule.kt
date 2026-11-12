package cloud.trotter.log.strength.di

import cloud.trotter.log.strength.run.GpsDistance
import cloud.trotter.log.strength.run.LocationSource
import cloud.trotter.log.strength.run.PlatformLocationSource
import cloud.trotter.log.strength.run.RunTracker
import cloud.trotter.log.strength.run.ServiceTrackingForeground
import cloud.trotter.log.strength.run.TrackingForeground
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RunDispatcher

@Module
@InstallIn(SingletonComponent::class)
object RunModule {
    @Provides fun locationSource(source: PlatformLocationSource): LocationSource = source
    @Provides fun trackingForeground(foreground: ServiceTrackingForeground): TrackingForeground = foreground
    @Provides fun gpsDistance(tracker: RunTracker): GpsDistance = tracker
    @Provides @RunDispatcher fun dispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
}
