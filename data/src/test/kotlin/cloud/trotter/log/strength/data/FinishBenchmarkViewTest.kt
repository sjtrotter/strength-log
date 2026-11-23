package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.progression.Benchmark
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.SharedProgression
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestRef
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishBenchmarkViewTest : FinishProgressionFixture() {
    @Test
    fun `case 49 a benchmark opening leaves the slot rows and rule alone and archives MEASURED`() = runTest {
        val plan = authored()
        val id = add(plan, "hand_release_pushup")
        val hrp = TestRef("aft_hrp", 1, 120)
        val prescription = Prescription(PrescribedTarget.RepsInTime(120, 20), test = hrp)
        repo.setPrescription(id, prescription)
        lifter(plan, id, work(w = 0.0, r = 20), rule(SlotRole.ACCESSORY, 20))
        val before = rule(id)
        assertEquals("B", repo.addDay(plan, "Day B"))
        val sibling = add(plan, "hand_release_pushup", "B")
        repo.setPrescription(sibling, prescription)
        lifter(plan, sibling, work(w = 0.0, r = 20), before, day = "B")
        val siblingRows = rows(plan, sibling, "B")
        val siblingRule = rule(sibling)
        assertTrue(SharedProgression.shares(
            db.programDao().exercise(id)!!.toDomain(),
            db.programDao().exercise(sibling)!!.toDomain(),
            db.planDao().plan(plan)!!.autoApply,
        ))
        val run = start(plan)
        val track = db.runDao().tracks(run).single { it.programExerciseId == id && it.slot == Slot.MAIN }
        val planned = listOf(Benchmark.row(TestEvents.get("aft_hrp"), hrp))
        db.runDao().updateTrack(track.copy(
            plannedJson = SetJson.encodeSets(planned),
            setsJson = SetJson.encodeSets(planned.mapIndexed { index, row -> row.copy(plannedIndex = index) }),
            openingJson = OpeningJson.encode(OpeningDto(benchmark = true)),
        ))
        perform(run, plan, id, 0.0, listOf(27))
        val session = assertNotNull(repo.finishRun(run))
        // #646: a sharing-eligible sibling must retain its whole rows and rule, not only its streak.
        assertEquals(siblingRows, rows(plan, sibling, "B"))
        assertEquals(siblingRule, rule(sibling))
        assertEquals(work(w = 0.0, r = 20), rows(plan, id))
        assertEquals(RepTarget.Fixed(20), rule(id).target)
        assertEquals(0, rule(id).state.successStreak)
        assertEquals(before, rule(id))
        assertEquals("MEASURED", SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single().reason)
        assertNull(offer(plan, "hand_release_pushup"))
    }
}
