package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toDomainOrNull
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test fun `a sprint time round trips through the existing columns`() {
        val result = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(30), hard = true),
            CardioSetStatus.FINISHED, 5, timing = SprintTime(452, TimingMethod.PHONE, StartProtocol.THREE_POINT))
        val entity = result.toEntity(1, 0)
        assertEquals(452, entity.centis)
        assertEquals("PHONE", entity.timingMethod)
        assertEquals("THREE_POINT", entity.startProtocol)
        assertEquals(result, entity.toDomain())
    }

    @Test fun `partial or unknown sprint timing is omitted at the read boundary`() {
        val entity = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(30)),
            CardioSetStatus.FINISHED, 5).toEntity(1, 0)
        for (invalid in listOf(
            entity.copy(centis = 452, startProtocol = "THREE_POINT"),
            entity.copy(centis = 452, timingMethod = "STOPWATCH", startProtocol = "THREE_POINT"),
        )) {
            assertNull(invalid.toDomainOrNull())
            assertFailsWith<IllegalArgumentException> { invalid.toDomain() }
        }
    }
}
