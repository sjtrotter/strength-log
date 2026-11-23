package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.standards.TestEvents
import kotlin.test.Test
import kotlin.test.assertEquals

class TestEventJsonTest {
    @Test
    fun `every shipped event round trips through its rules snapshot`() {
        TestEvents.all.forEach { event -> assertEquals(event, TestEventJson.decode(TestEventJson.encode(event))) }
    }
}
