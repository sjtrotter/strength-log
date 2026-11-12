package cloud.trotter.log.strength.run

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationAnswerTest {
    @Test fun `fine gives precise even without rationale`() {
        assertEquals(LocationAnswer.PRECISE, LocationAnswer.from(mapOf(Manifest.permission.ACCESS_FINE_LOCATION to true), false))
    }
    @Test fun `coarse alone gives approximate`() {
        assertEquals(LocationAnswer.APPROXIMATE, LocationAnswer.from(mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true), false))
    }
    @Test fun `neither grant distinguishes denial from blocked`() {
        assertEquals(LocationAnswer.DENIED, LocationAnswer.from(emptyMap(), true))
        assertEquals(LocationAnswer.BLOCKED, LocationAnswer.from(emptyMap(), false))
    }
}
