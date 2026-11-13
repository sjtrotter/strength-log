package cloud.trotter.log.strength.run

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import cloud.trotter.log.strength.domain.cardio.Fix
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@Singleton
open class FusedLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    open fun updates(): Flow<Fix> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val listener = LocationListener { location -> trySend(location.toFix()) }
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                close()
                return@callbackFlow
            }
            client.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                    .setMinUpdateIntervalMillis(1_000L)
                    .setMinUpdateDistanceMeters(0f).build(),
                ContextCompat.getMainExecutor(context), listener,
            ).addOnFailureListener { close(it) }
        } catch (_: SecurityException) {
            close()
        }
        awaitClose { client.removeLocationUpdates(listener) }
    }
}
