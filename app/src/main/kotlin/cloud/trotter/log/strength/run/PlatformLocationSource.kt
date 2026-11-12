package cloud.trotter.log.strength.run

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import cloud.trotter.log.strength.domain.cardio.Fix
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@Singleton
class PlatformLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSource {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun access(): LocationAccess = when {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
            if (LocationManagerCompat.isLocationEnabled(manager) && manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationAccess.PRECISE else LocationAccess.LOCATION_OFF
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
            LocationAccess.APPROXIMATE
        else -> LocationAccess.DENIED
    }

    override fun updates(): Flow<Fix> = callbackFlow {
        val listener = LocationListenerCompat { location ->
            trySend(Fix(location.elapsedRealtimeNanos / 1_000_000, location.latitude, location.longitude,
                if (location.hasAccuracy()) location.accuracy.toDouble() else Double.MAX_VALUE))
        }
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                close()
                return@callbackFlow
            }
            LocationManagerCompat.requestLocationUpdates(
                manager, LocationManager.GPS_PROVIDER,
                LocationRequestCompat.Builder(1_000L)
                    .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(1_000L)
                    .setMinUpdateDistanceMeters(0f).build(),
                ContextCompat.getMainExecutor(context), listener,
            )
        } catch (_: SecurityException) {
            close()
        }
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }
}
