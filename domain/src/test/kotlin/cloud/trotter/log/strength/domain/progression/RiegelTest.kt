package cloud.trotter.log.strength.domain.progression

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

class RiegelTest {
    @Test
    fun `case 20 goal checks round once and apply the marathon margin`() {
        assertEquals(2.084931, 2.0.pow(Riegel.EXPONENT), 1e-6)
        assertEquals(3127, Riegel.seconds(5000.0, 1500.0, 10000.0))
        assertEquals(13761, Riegel.seconds(21097.5, 6600.0, 42195.0))
        assertEquals(14449, Riegel.goalCheckSeconds(21097.5, 6600.0, 42195.0))
        assertEquals(3127, Riegel.goalCheckSeconds(5000.0, 1500.0, 10000.0))
    }
}
