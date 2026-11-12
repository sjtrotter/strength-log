package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardioRouteJsonTest {
    @Test fun `route points have a compact stable round trip`() {
        val points = listOf(RoutePoint(0, 0, 0), RoutePoint(4, 11, 0))
        val json = """{"v":1,"p":[[0,0,0],[4,11,0]]}"""
        assertEquals(json, CardioRouteJson.encode(points))
        assertEquals(points, CardioRouteJson.decode(json))
    }

    @Test fun `malformed versions points coordinates and times decode as null`() {
        listOf(
            """{"v":2,"p":[[0,0,0],[4,11,0]]}""",
            """{"v":1,"p":[[0,0,0]]}""",
            """{"v":1,"p":[[0,0,0],[4,9000001,0]]}""",
            """{"v":1,"p":[[5,0,0],[4,0,0]]}""",
            """{"v":1,"p":[[0,0,0],[4,11]]}""",
            "x",
        ).forEach { assertNull(CardioRouteJson.decode(it), it) }
    }
}
