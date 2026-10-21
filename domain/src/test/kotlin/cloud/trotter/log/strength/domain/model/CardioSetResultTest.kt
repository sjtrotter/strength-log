package cloud.trotter.log.strength.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CardioSetResultTest {
    private val spec = CardioSetSpec(CardioRole.WORK, Measure.Distance(400))

    @Test
    fun `unknown distance stays null for every end status`() {
        CardioSetStatus.entries.forEach { status ->
            val result = CardioSetResult(spec, status, 0)
            assertNull(result.metres)
            assertNull(result.distanceSource)
        }
    }

    @Test
    fun `known zero and positive distances require a source`() {
        DistanceSource.entries.forEach { source ->
            listOf(0, 380).forEach { metres ->
                val result = CardioSetResult(spec, CardioSetStatus.SHORTENED, 90, metres, source)
                assertEquals(metres, result.metres)
                assertEquals(source, result.distanceSource)
            }
        }
    }

    @Test
    fun `negative actuals and unpaired distance sources are rejected`() {
        assertFailsWith<IllegalArgumentException> { CardioSetResult(spec, CardioSetStatus.FINISHED, -1) }
        assertFailsWith<IllegalArgumentException> {
            CardioSetResult(spec, CardioSetStatus.SHORTENED, 0, -1, DistanceSource.MANUAL)
        }
        listOf(0, 380).forEach { metres ->
            assertFailsWith<IllegalArgumentException> { CardioSetResult(spec, CardioSetStatus.FINISHED, 0, metres) }
        }
        DistanceSource.entries.forEach { source ->
            assertFailsWith<IllegalArgumentException> {
                CardioSetResult(spec, CardioSetStatus.SKIPPED, 0, distanceSource = source)
            }
        }
    }
}
