package cloud.trotter.log.strength.run

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.domain.cardio.Fix
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformLocationSourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val source = PlatformLocationSource(app)

    @Test fun `platform elapsed time and accuracy become a fix`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fixes = mutableListOf<Fix>()
        val job = backgroundScope.launch { source.updates().toList(fixes) }
        runCurrent()
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 41.0; longitude = -87.0; elapsedRealtimeNanos = 5_000_000_000; accuracy = 4f
        })
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(Fix(5000, 41.0, -87.0, 4.0), fixes.single())
        job.cancel()
    }

    @Test fun `missing accuracy remains unknown`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fixes = mutableListOf<Fix>()
        val job = backgroundScope.launch { source.updates().toList(fixes) }
        runCurrent()
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 41.0; longitude = -87.0; elapsedRealtimeNanos = 5_000_000_000
        })
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(Double.MAX_VALUE, fixes.single().accuracyMetres, 0.0)
        job.cancel()
    }

    @Test fun `without fine updates complete empty and coarse is approximate`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(LocationAccess.DENIED, source.access())
        assertEquals(emptyList<Fix>(), source.updates().toList())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(LocationAccess.APPROXIMATE, source.access())
        assertEquals(emptyList<Fix>(), source.updates().toList())
    }
}
