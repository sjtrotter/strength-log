package cloud.trotter.log.strength.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class GoalTimeTest {
    @Test fun `running floors round down with a one second minimum`() {
        for (activity in CardioFamily.RUN.activities) {
            for ((metres, seconds) in listOf(0 to 1, 9 to 1, 19 to 1, 800 to 80, 1500 to 150, 42195 to 4219)) {
                assertEquals(seconds, GoalTime.floorSeconds(metres, activity))
            }
        }
    }

    @Test fun `every other activity has a one second floor`() {
        for (activity in CardioActivity.entries.filter { CardioFamily.of(it) != CardioFamily.RUN }) {
            for (metres in listOf(1, 40_000, 200_000)) {
                assertEquals(1, GoalTime.floorSeconds(metres, activity))
            }
        }
    }
}
