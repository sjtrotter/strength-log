package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Case38Test : FinishProgressionFixture() {
    @Test fun `case 38 hand added lifts store prescribed rows and rules and the curl moves reps before load`() = runTest {
        val plan = authored()
        repo.addExercise("A", ProgramExercise("hammer_curl", targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(30.0, 8))), plan)
        val curl = id(plan, "hammer_curl")
        repo.addExercise("A", ProgramExercise("bb_bench", SetStructure.RAMP_TOP_BACKOFF, 3,
            prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5))), plan)
        val bench = id(plan, "bb_bench")

        assertEquals(work(3, 30.0, 8), rows(plan, curl))
        assertEquals(SlotRule.defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH,
            SetStructure.STRAIGHT, 8, MovementPattern.BICEPS), rule(curl))
        val benchRows = listOf(
            LoggedSet(100.0, 5, SetKind.RAMP), LoggedSet(130.0, 5, SetKind.RAMP),
            LoggedSet(150.0, 5, SetKind.RAMP), LoggedSet(165.0, 3, SetKind.RAMP),
            LoggedSet(185.0, 5, SetKind.TOP), LoggedSet(140.0, 8, SetKind.BACKOFF),
        )
        assertEquals(benchRows, rows(plan, bench))
        assertEquals(SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH,
            SetStructure.RAMP_TOP_BACKOFF, 5, MovementPattern.H_PUSH), rule(bench))
        val benchRuleJson = db.programDao().exercise(bench)!!.ruleJson

        val first = start(plan)
        db.runDao().tracks(first).forEach { assertNull(it.openingJson) }
        assertIs<RunWriteResult.InRun>(repo.skipLift(plan, "A", bench, Slot.MAIN, "bb_bench", null))
        perform(first, plan, curl, 30.0, listOf(12, 12, 12))
        repo.finishRun(first)
        advanceDays(1)

        assertEquals(work(3, 30.0, 12), rows(plan, curl))
        assertEquals(RepTarget.Range(8, 12, 12), rule(curl).target)
        assertEquals(benchRows, rows(plan, bench))
        assertEquals(benchRuleJson, db.programDao().exercise(bench)!!.ruleJson)

        val second = start(plan)
        db.runDao().tracks(second).forEach { assertNull(it.openingJson) }
        assertIs<RunWriteResult.InRun>(repo.skipLift(plan, "A", bench, Slot.MAIN, "bb_bench", null))
        perform(second, plan, curl, 30.0, listOf(12, 12, 12))
        repo.finishRun(second)

        assertEquals(work(3, 35.0, 8), rows(plan, curl))
        assertEquals(RepTarget.Range(8, 12, 8), rule(curl).target)
        assertEquals(benchRows, rows(plan, bench))
        assertEquals(benchRuleJson, db.programDao().exercise(bench)!!.ruleJson)
    }
}
