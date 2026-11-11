package cloud.trotter.log.strength.domain.progression

import kotlin.test.Test
import kotlin.test.assertEquals

class EffortTest {
    @Test fun `of maps stored rir to the four words`() {
        assertEquals(
            listOf(null, Effort.MAX, Effort.HARD, Effort.GOOD, null, Effort.EASY, Effort.EASY),
            listOf(null, 0, 1, 2, 3, 4, 5).map { Effort.of(it) },
        )
    }
}
