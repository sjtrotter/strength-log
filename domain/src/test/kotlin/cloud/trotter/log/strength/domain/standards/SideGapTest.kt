package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.Side
import cloud.trotter.log.strength.domain.model.SideResult
import cloud.trotter.log.strength.domain.model.Sides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SideGapTest {
    private fun hold(left: Int, right: Int): LoggedSet {
        val sides = Sides(SideResult(0.0, 0, left), SideResult(0.0, 0, right))
        return LoggedSet(0.0, 0, SetKind.WORK, true, sides.seconds, sides = sides)
    }

    @Test fun `the same side lower in three sessions is named`() {
        assertEquals(Side.LEFT, SideGap.repeated(List(3) { listOf(hold(40, 52)) }))
        assertEquals(Side.RIGHT, SideGap.repeated(List(4) { listOf(hold(52, 40)) }))
        assertEquals(Side.LEFT, SideGap.lowerSide(listOf(hold(40, 52), hold(20, 12))))
    }

    @Test fun `two sessions are not enough`() {
        assertNull(SideGap.repeated(List(2) { listOf(hold(40, 52)) }))
    }

    @Test fun `a session without sides breaks the run`() {
        assertNull(SideGap.repeated(listOf(listOf(hold(40, 52)),
            listOf(hold(40, 52).copy(sides = null)), listOf(hold(40, 52)))))
    }

    @Test fun `alternating sides name nothing`() {
        assertNull(SideGap.repeated(listOf(listOf(hold(40, 52)), listOf(hold(52, 40)), listOf(hold(40, 52)))))
    }

    @Test fun `a tie names nothing`() {
        assertNull(SideGap.repeated(listOf(listOf(hold(40, 52)), listOf(hold(40, 40)), listOf(hold(40, 52)))))
    }

    @Test fun `loaded lifts compare load times reps`() {
        val sides = Sides(SideResult(25.0, 10, 0), SideResult(30.0, 8, 0))
        val set = LoggedSet(sides.weightLb, sides.reps, SetKind.WORK, true, sides = sides)
        assertEquals(Side.RIGHT, SideGap.repeated(List(3) { listOf(set) }))
    }

    @Test fun `rep sets compare their totals and older sessions do not change the run`() {
        val sides = Sides(SideResult(0.0, 8, 0), SideResult(0.0, 10, 0))
        val set = LoggedSet(0.0, 8, SetKind.WORK, true, sides = sides)
        assertEquals(Side.LEFT, SideGap.repeated(List(3) { listOf(set) } + listOf(listOf(set.copy(sides = null)))))
    }
}
