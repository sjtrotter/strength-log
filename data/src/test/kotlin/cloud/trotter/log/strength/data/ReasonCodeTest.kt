package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.ReasonCode
import cloud.trotter.log.strength.domain.progression.Reason
import kotlin.test.assertEquals
import org.junit.Test

class ReasonCodeTest {
    @Test fun `Followed round trips as FOLLOWED`() {
        assertEquals("FOLLOWED", ReasonCode.encode(Reason.Followed))
        assertEquals(Reason.Followed, ReasonCode.decode("FOLLOWED"))
    }
}
