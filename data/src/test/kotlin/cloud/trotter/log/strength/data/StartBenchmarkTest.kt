package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.TestResultEntity
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.TestEventJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.model.TestGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestRef
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartBenchmarkTest : FinishProgressionFixture() {
    private val hrp = TestRef("aft_hrp", 1, 120)

    private suspend fun prep(plan: Long, prescription: Prescription, exercise: String = "hand_release_pushup"): Long {
        val id = add(plan, exercise)
        repo.setPrescription(id, prescription)
        if (prescription.target is PrescribedTarget.RepsInTime) {
            lifter(plan, id, work(w = 0.0, r = 20), rule(SlotRole.ACCESSORY, 20))
        }
        return id
    }

    private suspend fun opened(plan: Long) = db.runDao().tracks(start(plan)).single { it.slot == Slot.MAIN }

    private suspend fun discard(plan: Long) {
        assertTrue(repo.discardRun(db.programDao().day(plan, "A")!!.nextRunId))
    }

    @Test fun `case 57 a prep slot on another movement never opens as a benchmark`() = runTest {
        val plan = authored()
        prep(plan, Prescription(PrescribedTarget.RepsInTime(120, 5), test = hrp), "knee_pushup")
        repo.plans.setTestGoal(plan, TestGoal(listOf(TestEventGoal(hrp, 40))))
        assertNull(opened(plan).openingJson)
    }

    @Test fun `case 60 a pressure caution benchmark opens at its cap`() = runTest {
        val plan = authored()
        val ref = TestRef("wall_sit:MAX_HOLD", 1)
        prep(plan, Prescription(PrescribedTarget.Hold(55), test = ref), "wall_sit")
        repo.plans.setTestGoal(plan, TestGoal(listOf(TestEventGoal(ref, 90))))
        val track = opened(plan)
        assertTrue(OpeningJson.decode(track.openingJson)!!.benchmark)
        assertEquals(listOf(LoggedSet(0.0, 0, SetKind.WORK, seconds = 120)), SetJson.decodeSets(track.plannedJson))
    }

    @Test fun `a derived max unbroken event opens without rewriting its ref`() = runTest {
        val plan = authored()
        val ref = TestRef("pullup:REPS_CONTINUOUS", 1)
        prep(plan, Prescription(PrescribedTarget.Reps(5), test = ref), "pullup")
        repo.plans.setTestGoal(plan, TestGoal(listOf(TestEventGoal(ref, 20))))
        val track = opened(plan)
        assertTrue(OpeningJson.decode(track.openingJson)!!.benchmark)
        assertEquals(listOf(LoggedSet(0.0, 0, SetKind.WORK)), SetJson.decodeSets(track.plannedJson))
    }

    @Test fun `case 51 a due prep slot opens as a benchmark and a fresh result closes it`() = runTest {
        val plan = authored()
        val id = prep(plan, Prescription(PrescribedTarget.RepsInTime(120, 20), test = hrp))
        repo.plans.setTestGoal(plan, TestGoal(listOf(TestEventGoal(hrp, 40))))
        val beforeRows = rows(plan, id)
        val beforeRule = rule(id)
        val first = opened(plan)
        assertEquals(listOf(LoggedSet(0.0, 0, SetKind.WORK)), SetJson.decodeSets(first.plannedJson))
        assertTrue(OpeningJson.decode(first.openingJson)!!.benchmark)
        assertEquals(0, SetJson.decodeSets(first.setsJson).single().plannedIndex)
        assertFalse(SetJson.decodeSets(first.setsJson).single().done)
        assertEquals(beforeRows, rows(plan, id))
        assertEquals(beforeRule, rule(id))
        discard(plan)
        db.testResultDao().insert(TestResultEntity(
            completedAt = clock.millis(), planId = plan, dayId = "A", runId = first.runId,
            programExerciseId = id, setIndex = 0, exerciseId = "hand_release_pushup",
            eventId = hrp.eventId, eventVersion = hrp.version, windowSeconds = hrp.windowSeconds,
            count = 27, seconds = null, stop = "TIME_UP", rulesJson = TestEventJson.encode(TestEvents.get(hrp.eventId)),
        ))
        val fresh = opened(plan)
        assertEquals(work(w = 0.0, r = 20), SetJson.decodeSets(fresh.plannedJson))
        assertNull(fresh.openingJson)
        discard(plan)
        advanceDays(20)
        assertNull(opened(plan).openingJson)
        discard(plan)
        advanceDays(1)
        assertTrue(OpeningJson.decode(opened(plan).openingJson)!!.benchmark)
    }

    @Test fun `a plank prep slot opens as a hold at the ceiling`() = runTest {
        val plan = authored()
        val ref = TestRef("aft_plank", 1)
        prep(plan, Prescription(PrescribedTarget.Hold(60), test = ref), "plank")
        repo.plans.setTestGoal(plan, TestGoal(listOf(TestEventGoal(ref, 220))))
        assertEquals(listOf(LoggedSet(0.0, 0, SetKind.WORK, seconds = 220)), SetJson.decodeSets(opened(plan).plannedJson))
    }

    @Test fun `no benchmark without a goal naming the event or inside the last week`() = runTest {
        val plan = authored()
        prep(plan, Prescription(PrescribedTarget.RepsInTime(120, 20), test = hrp))
        assertNull(opened(plan).openingJson)
        discard(plan)
        repo.plans.setTestGoal(plan, TestGoal(listOf(TestEventGoal(TestRef("aft_plank", 1), 220))))
        assertNull(opened(plan).openingJson)
        discard(plan)
        val today = LocalDate.now(clock).toEpochDay()
        repo.plans.setTestGoal(plan, TestGoal(listOf(TestEventGoal(hrp, 40)), today + 6))
        assertNull(opened(plan).openingJson)
        discard(plan)
        repo.plans.setTestGoal(plan, TestGoal(listOf(TestEventGoal(hrp, 40)), today + 7))
        assertTrue(OpeningJson.decode(opened(plan).openingJson)!!.benchmark)
    }

    @Test fun `a benchmark link runs only on its event movement`() = runTest {
        val plan = authored()
        val prescription = Prescription(PrescribedTarget.RepsInTime(120, 20), test = hrp)
        val other = prep(plan, prescription, "pushup")
        val matching = prep(plan, prescription)
        repo.plans.setTestGoal(plan, TestGoal(listOf(TestEventGoal(hrp, 40))))
        val tracks = db.runDao().tracks(start(plan))
        assertNull(tracks.single { it.programExerciseId == other }.openingJson)
        assertTrue(OpeningJson.decode(tracks.single { it.programExerciseId == matching }.openingJson)!!.benchmark)
    }

    @Test fun `a custom benchmark opens in the linked window and leaves its training window alone`() = runTest {
        val plan = authored()
        val ref = TestRef("custom_pushup_window", 1, 60)
        val id = prep(plan, Prescription(PrescribedTarget.RepsInTime(90, 20), test = ref), "pushup")
        repo.plans.setTestGoal(plan, TestGoal(listOf(TestEventGoal(ref, 40))))
        assertTrue(OpeningJson.decode(opened(plan).openingJson)!!.benchmark)
        assertEquals(work(w = 0.0, r = 20), rows(plan, id))
    }
}
