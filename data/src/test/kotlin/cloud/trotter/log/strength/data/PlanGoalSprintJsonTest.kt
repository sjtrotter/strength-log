package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanGoalSprintJsonTest {
    private val sprint = """{"type":"sprint","metres":30,"targetCentis":440,"sprintAttempts":[{"completedAt":1,"centis":452,"timingMethod":"PHONE","startProtocol":"THREE_POINT"}]}"""

    @Test fun `timed goals without a time and sprint goals validate without a domain projection`() {
        for (json in listOf("""{"type":"timed","activity":"ROAD_RUN","metres":5000}""", sprint,
            """{"type":"sprint","metres":1}""", """{"type":"sprint","metres":1000,"targetCentis":360000}""")) {
            PlanGoalJson.validate(json)
            assertNull(PlanGoalJson.decode(json))
        }
    }

    @Test fun `invalid sprint and no-time values reject`() {
        val invalid = listOf(
            """{"type":"timed","activity":"ROAD_RUN","metres":5000,"underSeconds":0}""",
            sprint.replace("metres\":30", "metres\":0"), sprint.replace("metres\":30", "metres\":1001"),
            sprint.replace("targetCentis\":440", "targetCentis\":0"),
            sprint.replace("centis\":452", "centis\":0"), sprint.replace("centis\":452", "centis\":360001"),
            sprint.replace("PHONE", "phone"), sprint.replace("THREE_POINT", "three_point"),
            sprint.replace("\"completedAt\":1,", ""),
        )
        for (json in invalid) {
            assertNull(PlanGoalJson.decode(json))
            assertFailsWith<IllegalArgumentException> { PlanGoalJson.validate(json) }
        }
    }

    @Test fun `existing Timed and Shuttle goals round trip`() {
        for (goal in listOf(PlanGoal.Timed(CardioActivity.ROAD_RUN, 5000, 1500), PlanGoal.Shuttle("PACER", 52))) {
            assertEquals(goal, PlanGoalJson.decode(PlanGoalJson.encode(goal)))
        }
    }
}
