package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardioAtEndMappingTest {
    @Test fun `case 18 at-end readings round trip without sprint timing`() {
        val result = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(900)),
            CardioSetStatus.FINISHED, 904, metres = 3485, distanceSource = DistanceSource.MANUAL, metresAtEnd = 3470, secondsAtEnd = 900)
        val entity = result.toEntity(1, 0)
        assertEquals(result, entity.toDomain())
        assertNull(entity.centis)
        assertNull(entity.timingMethod)
        assertNull(entity.startProtocol)
    }
}
