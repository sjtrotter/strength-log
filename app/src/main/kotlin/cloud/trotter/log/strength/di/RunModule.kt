package cloud.trotter.log.strength.di

import android.content.Context
import cloud.trotter.log.strength.run.GpsDistance
import cloud.trotter.log.strength.run.LocationSource
import cloud.trotter.log.strength.run.PlayServicesCheck
import cloud.trotter.log.strength.run.PreferredLocationSource
import cloud.trotter.log.strength.run.RunTracker
import cloud.trotter.log.strength.run.ServiceTrackingForeground
import cloud.trotter.log.strength.run.TrackingForeground
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @Provides fun locationSource(source: PreferredLocationSource): LocationSource = source
    @Provides fun playServicesCheck(@ApplicationContext context: Context): PlayServicesCheck = PlayServicesCheck {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
    @Provides fun trackingForeground(foreground: ServiceTrackingForeground): TrackingForeground = foreground
    @Provides fun gpsDistance(tracker: RunTracker): GpsDistance = tracker
    @Provides @RunDispatcher fun dispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
}
