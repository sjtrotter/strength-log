package cloud.trotter.log.strength

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestPermissionsTest {
    @Test fun `the app must never request the INTERNET permission`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        assertNotNull(permissions)
        assertTrue(permissions.orEmpty().contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(permissions.orEmpty().contains(Manifest.permission.INTERNET))
    }
}
