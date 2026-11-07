package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ShuttleAttempt
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupV25Test {
    private val codec = BackupCodec()
    private fun document(goalJson: String?) = BackupDocument(
        settings = SettingsBackup(180, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Plan", "AUTHORED", 1000, goalJson = goalJson,
            days = listOf(ProgramDayBackup("A", "Day A", "")))),
    )

    @Test fun `v25 goals round trip verbatim with dates and shuttle attempts`() {
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

    @Test fun `v9 through v24 carrying goalJson restore null`() {
        val goal = PlanGoalJson.encode(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960))
        for (version in 9..24) {
            val legacy = document(goal).copy(schemaVersion = version)
            assertNull(legacy.toSnapshot().plans.single().goalJson)
            assertNull(codec.decode(codec.encode(legacy)).toSnapshot().plans.single().goalJson)
            val encoded = codec.encode(document(null).copy(schemaVersion = version))
            val poisoned = encoded.replace("\"goalJson\":null", "\"goalJson\":{}")
            assertNull(codec.decode(poisoned).toSnapshot().plans.single().goalJson)
        }
    }

    @Test fun `v24 Rest entries restore without a goal and v25 preserves both`() {
        val payload = PlanGoalJson.encode(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960))
        val base = document(payload)
        val withRest = base.copy(plans = listOf(base.plans.single().copy(
            suggestedDay = "B",
            days = base.plans.single().days + ProgramDayBackup("B", "Rest", "", kind = "REST"),
        )))
        for (version in 24..25) {
            val source = withRest.copy(schemaVersion = version)
            val snapshot = codec.decode(codec.encode(source)).toSnapshot()
            assertEquals("REST", snapshot.days.last().kind)
            assertEquals("B", snapshot.plans.single().suggestedDayId)
            assertEquals(if (version == 25) payload else null, snapshot.plans.single().goalJson)
            assertEquals(snapshot, codec.decode(codec.encode(snapshot.toDocument())).toSnapshot())
        }
    }

    @Test fun `invalid goals fail as inconsistent`() {
        val goal = PlanGoalJson.encode(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960))
        for (payload in listOf("junk", "{}", goal.replace("timed", "future"), goal.replace("3219", "0"))) {
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document(payload))) }
        }
    }

    @Test fun `v25 goals accept absent dates and dates from 2000 through 2100`() {
        for (day in listOf(null, LocalDate.of(2000, 1, 1).toEpochDay(), LocalDate.of(2100, 12, 31).toEpochDay())) {
            val goals = listOf(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960, day),
                PlanGoal.Shuttle("PACER", 52, byEpochDay = day))
            for (goal in goals) {
                val payload = PlanGoalJson.encode(goal)
                val snapshot = codec.decode(codec.encode(document(payload))).toSnapshot()
                assertEquals(payload, snapshot.plans.single().goalJson)
                assertEquals(goal, PlanGoalJson.decode(snapshot.plans.single().goalJson))
                assertEquals(snapshot, codec.decode(codec.encode(snapshot.toDocument())).toSnapshot())
            }
        }
    }

    @Test fun `v25 goals with dates outside 2000 through 2100 fail as inconsistent`() {
        for (day in listOf(LocalDate.of(1999, 12, 31).toEpochDay(), LocalDate.of(2101, 1, 1).toEpochDay(),
            0L, LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay(),
            Long.MIN_VALUE, Long.MAX_VALUE)) {
            val goals = listOf(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960, day),
                PlanGoal.Shuttle("PACER", 52, byEpochDay = day))
            for (goal in goals) {
                val payload = PlanGoalJson.encode(goal)
                assertFailsWith<BackupError.Inconsistent>(payload) { codec.decode(codec.encode(document(payload))) }
            }
        }
    }

    @Test fun `v25 without a goal restores null`() {
        val encoded = codec.encode(document(null)).replace(",\"goalJson\":null", "")
        assertNull(codec.decode(encoded).toSnapshot().plans.single().goalJson)
    }
}
