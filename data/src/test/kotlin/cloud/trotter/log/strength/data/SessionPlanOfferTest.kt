package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.OpeningDto
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
        for (weight in listOf("-1.0", "NaN", "Infinity", "-Infinity", "1e999")) {
            assertFailsWith<IllegalArgumentException> {
                SessionPlanJson.decode("""[{"position":0,"exerciseId":"bb_bench","slot":"main","sets":[],"offer":{"fromLb":$weight,"toLb":190.0}}]""")
            }
            assertFailsWith<IllegalArgumentException> {
                SessionPlanJson.decode("""[{"position":0,"exerciseId":"bb_bench","slot":"main","sets":[],"offer":{"fromLb":185.0,"toLb":$weight}}]""")
            }
        }
    }

    @Test fun `an archived offer tolerates an obsolete nested rule`() {
        val offer = OfferDto(185.0, 190.0, beforeSets = emptyList(), afterSets = emptyList(),
            beforeRuleJson = "obsolete", afterRuleJson = "obsolete")
        val dto = PlannedTrackDto(0, "bb_bench", "main", sets = emptyList(), offer = offer)
        assertEquals(listOf(dto), SessionPlanJson.decode(SessionPlanJson.encode(listOf(dto))))
    }
    @Test fun `an archived opening round trips and a bad one fails decode`() {
        val track = PlannedTrackDto(0, "bb_bench", "main", sets = emptyList(), opening = OpeningDto("SCALED", 0.95))
        assertEquals(listOf(track), SessionPlanJson.decode(SessionPlanJson.encode(listOf(track))))
        val normal = track.copy(opening = null)
        assertEquals("""[{"position":0,"exerciseId":"bb_bench","slot":"main","sets":[]}]""", SessionPlanJson.encode(listOf(normal)))
        for (opening in listOf("""{"layoff":"SCALED","factor":1.5}""", """{"layoff":"HOLD","factor":0.9}""", """{"layoff":"LATER"}""")) {
            assertFailsWith<IllegalArgumentException> {
                SessionPlanJson.decode("""[{"position":0,"exerciseId":"bb_bench","slot":"main","sets":[],"opening":$opening}]""")
            }
        }
    }

}
