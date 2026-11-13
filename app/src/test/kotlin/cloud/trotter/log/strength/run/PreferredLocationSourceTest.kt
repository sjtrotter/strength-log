package cloud.trotter.log.strength.run

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.domain.cardio.Fix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferredLocationSourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val platformFix = Fix(1000, 41.0, -87.0, 5.0)
    private val fusedFixes = listOf(Fix(2000, 41.1, -87.1, 4.0), Fix(3000, 41.2, -87.2, 3.0))
    private var available = true
    private var checks = 0
    private var platformCollections = 0
    private var fusedCollections = 0
    private var platformAccess = LocationAccess.PRECISE
    private val platform = object : PlatformLocationSource(app) {
        override fun access(): LocationAccess = platformAccess
        override fun updates(): Flow<Fix> = flow {
            platformCollections++
            emit(platformFix)
        }
    }
    private val fused = object : FusedLocationSource(app) {
        override fun updates(): Flow<Fix> = flow {
            fusedCollections++
            emit(fusedFixes.first())
            available = false
            emit(fusedFixes.last())
        }
    }
    private val source = PreferredLocationSource(platform, fused, PlayServicesCheck { checks++; available })

    @Test fun `available Play services collects only fused fixes`() = runTest {
        assertEquals(fusedFixes, source.updates().toList())
        assertEquals(1, fusedCollections)
        assertEquals(0, platformCollections)
        assertEquals(1, checks)
    }

    @Test fun `unavailable Play services collects only platform fixes`() = runTest {
        available = false
        assertEquals(listOf(platformFix), source.updates().toList())
        assertEquals(0, fusedCollections)
        assertEquals(1, platformCollections)
        assertEquals(1, checks)
    }

    @Test fun `provider choice is lazy and stays fixed until the next collection`() = runTest {
        val updates = source.updates()
        assertEquals(0, checks)
        assertEquals(fusedFixes, updates.toList())
        assertEquals(1, checks)
        assertEquals(listOf(platformFix), updates.toList())
        assertEquals(2, checks)
        assertEquals(1, fusedCollections)
        assertEquals(1, platformCollections)
    }

    @Test fun `access always returns the platform value`() {
        for (value in LocationAccess.entries) {
            platformAccess = value
            assertEquals(value, source.access())
        }
        assertEquals(0, checks)
        assertEquals(0, fusedCollections)
        assertEquals(0, platformCollections)
    }
}
