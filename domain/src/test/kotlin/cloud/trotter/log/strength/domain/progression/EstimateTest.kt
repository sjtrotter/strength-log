package cloud.trotter.log.strength.domain.progression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EstimateTest {
    @Test
    fun `case 4 Epley is trusted only through ten reps including reserve`() {
        val estimate = assertNotNull(Estimate.e1rm(185.0, 8, 2))
        assertEquals(246.66666666666666, estimate, 1e-9)
        assertEquals(200.0, Estimate.loadFor(estimate, 5, 2), 1e-9)
        assertNull(Estimate.e1rm(135.0, 12, 2))
    }

    @Test
    fun `case 27 history estimate adjusts for a different target`() {
        val estimate = assertNotNull(Estimate.e1rm(225.0, 5, 2))
        assertEquals(277.5, estimate, 1e-9)
        assertEquals(198.21, Estimate.loadFor(estimate, 10, 2), 1e-2)
    }
}
