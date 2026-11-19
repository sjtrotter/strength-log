package cloud.trotter.log.strength.domain.progression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunSessionTest {
    @Test fun `only threshold and intervals take turns`() {
        for (session in RunSession.entries) {
            when (session) {
                RunSession.THRESHOLD -> assertEquals(RunSession.VO2MAX, session.alternation)
                RunSession.VO2MAX -> assertEquals(RunSession.THRESHOLD, session.alternation)
                else -> assertNull(session.alternation)
            }
            session.alternation?.let { assertEquals(session, it.alternation) }
        }
    }
}
