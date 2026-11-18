package cloud.trotter.log.strength.domain.planner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SprintSetupReadsTest {
    @Test fun `the public floor and planner use the same resolved mains`() {
        val answers = SetupAnswers(branch = PlanBranch.BOTH, both = BothAnswers(sprint = true), mains = listOf("bb_bench"))
        assertEquals(SprintFloor.SQUAT_OR_HINGE, Planner.sprintLowerMainMissing(answers))
        assertFailsWith<IllegalArgumentException> { Planner.plan(answers) }
        val hinge = answers.copy(mains = listOf("trap_dl"))
        assertNull(Planner.sprintLowerMainMissing(hinge))
        Planner.plan(hinge)
        val careful = hinge.copy(sprint = SprintAnswers(fortyOrOlder = true))
        assertEquals(SprintFloor.SQUAT, Planner.sprintLowerMainMissing(careful))
        assertFailsWith<IllegalArgumentException> { Planner.plan(careful) }
        val squat = careful.copy(mains = listOf("bb_back_squat"))
        assertNull(Planner.sprintLowerMainMissing(squat))
        Planner.plan(squat)
        assertNull(Planner.sprintLowerMainMissing(answers.copy(branch = PlanBranch.SPRINT)))
        assertNull(Planner.sprintLowerMainMissing(answers.copy(both = BothAnswers())))
    }
}
