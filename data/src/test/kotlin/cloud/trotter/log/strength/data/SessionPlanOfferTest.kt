package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.OfferDto
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionPlanOfferTest {
    @Test fun `an absent offer keeps the archived track bytes unchanged`() {
        val json = """[{"position":0,"exerciseId":"bb_bench","slot":"main","sets":[]}]"""
        assertEquals(json, SessionPlanJson.encode(listOf(PlannedTrackDto(0, "bb_bench", "main", sets = emptyList()))))
        assertEquals(json, SessionPlanJson.encode(SessionPlanJson.decode(json)))
    }

    @Test fun `an offer round trips and rejects invalid load pairs`() {
        val dto = PlannedTrackDto(0, "bb_bench", "main", sets = emptyList(), offer = OfferDto(185.0, 190.0))
        assertEquals(listOf(dto), SessionPlanJson.decode(SessionPlanJson.encode(listOf(dto))))
        for (weight in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { OfferDto(weight, 190.0) }
            assertFailsWith<IllegalArgumentException> { OfferDto(185.0, weight) }
        }
    }
}
