package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.TestAnswer
import cloud.trotter.log.strength.domain.standards.TestRef
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishTestPrepTest : FinishProgressionFixture() {
    @Test fun `case 59 a full max unbroken exposure moves the aim from 5 to 6`() = runTest {
        val ref = TestRef("pullup:REPS_CONTINUOUS", 1)
        val answers = SetupAnswers(tests = listOf(TestAnswer(TestEventGoal(ref, 20), 8)),
            testDateEpochDay = LocalDate.now(clock).toEpochDay() + 6)
        val plan = repo.plans.createPlannedPlan("Prep", answers)
        repo.plans.publishPlannedPlan(plan)
        val slot = db.programDao().exercisesForDay(plan, "A")
            .single { PrescriptionJson.decode(it.prescriptionJson)?.test == ref }
        assertEquals(listOf(5, 5, 5), rows(plan, slot.id).map { it.reps })
        val run = start(plan)
        repo.applyToRun(run, plan, "A", listOf(TrackEdit(slot.id, Slot.MAIN) { rows -> rows.map { it.copy(done = true) } }))
        assertNotNull(repo.finishRun(run))
        assertEquals(listOf(6, 6, 6), rows(plan, slot.id).map { it.reps })
        assertEquals(6, rule(slot.id).target.reps)
    }

    @Test fun `a generated prep hold progresses to its required number above the catalog ceiling`() = runTest {
        val ref = TestRef("aft_plank", 1)
        val answers = SetupAnswers(tests = listOf(TestAnswer(TestEventGoal(ref, 220), 430)),
            testDateEpochDay = LocalDate.now(clock).toEpochDay() + 6)
        val plan = repo.plans.createPlannedPlan("Prep", answers)
        repo.plans.publishPlannedPlan(plan)
        val slot = db.programDao().exercisesForDay(plan, "A")
            .single { PrescriptionJson.decode(it.prescriptionJson)?.test == ref }
        assertEquals(listOf(215, 215, 215), rows(plan, slot.id).map { it.seconds })
        for (expectedReason in listOf("HOLD_STEP", "HOLD_CEILING")) {
            val run = start(plan)
            repo.applyToRun(run, plan, "A", listOf(TrackEdit(slot.id, Slot.MAIN) { rows ->
                rows.map { it.copy(done = true) }
            }))
            assertNotNull(repo.finishRun(run))
            assertEquals(listOf(220, 220, 220), rows(plan, slot.id).map { it.seconds })
            assertEquals(expectedReason, repo.history.slotSessionsById(plan, "A").getValue(slot.id to Slot.MAIN).reason)
            advanceDays(1)
        }
    }
}
