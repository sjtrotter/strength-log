package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ShuttleAttempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupV24Test {
    private val codec = BackupCodec()
    private fun document(goalJson: String?) = BackupDocument(
        settings = SettingsBackup(180, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Plan", "AUTHORED", 1000, goalJson = goalJson,
            days = listOf(ProgramDayBackup("A", "Day A", "")))),
    )

    @Test fun `v24 goals round trip verbatim with dates and shuttle attempts`() {
        val goals = listOf(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960, 21000),
            PlanGoal.Shuttle("PACER", 52, listOf(ShuttleAttempt(1000, 42), ShuttleAttempt(2000, 55)), 21001))
        for (goal in goals) {
            val payload = PlanGoalJson.encode(goal).replace(",", ", ")
            val snapshot = codec.decode(codec.encode(document(payload))).toSnapshot()
            assertEquals(payload, snapshot.plans.single().goalJson)
            val exported = snapshot.toDocument()
            assertEquals(CURRENT_SCHEMA_VERSION, exported.schemaVersion)
            assertEquals(payload, exported.plans.single().goalJson)
            assertEquals(snapshot, codec.decode(codec.encode(exported)).toSnapshot())
        }
    }

    @Test fun `v22 and v23 carrying goalJson restore null`() {
        val goal = PlanGoalJson.encode(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960))
        for (version in 9..23) {
            val legacy = document(goal).copy(schemaVersion = version)
            assertNull(legacy.toSnapshot().plans.single().goalJson)
            assertNull(codec.decode(codec.encode(legacy)).toSnapshot().plans.single().goalJson)
            val encoded = codec.encode(document(null).copy(schemaVersion = version))
            val poisoned = encoded.replace("\"goalJson\":null", "\"goalJson\":{}")
            assertNull(codec.decode(poisoned).toSnapshot().plans.single().goalJson)
        }
    }

    @Test fun `invalid goals fail as inconsistent`() {
        val goal = PlanGoalJson.encode(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960))
        for (payload in listOf("junk", "{}", goal.replace("timed", "future"), goal.replace("3219", "0"))) {
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document(payload))) }
        }
    }

    @Test fun `v24 without a goal restores null`() {
        val encoded = codec.encode(document(null)).replace(",\"goalJson\":null", "")
        assertNull(codec.decode(encoded).toSnapshot().plans.single().goalJson)
    }
}
