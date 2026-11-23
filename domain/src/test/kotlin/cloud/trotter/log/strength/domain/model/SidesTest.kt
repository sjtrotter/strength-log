package cloud.trotter.log.strength.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidesTest {
    private val row = LoggedSet(0.0, 0, SetKind.WORK)
    private fun LoggedSet.sided(left: SideResult, right: SideResult) = Sides(left, right).let {
        copy(weightLb = it.weightLb, reps = it.reps, seconds = it.seconds, sides = it)
    }
    private val split = row.sided(SideResult(25.0, 10, 0), SideResult(25.0, 8, 0))

    @Test fun `the set's values are the weaker side field by field`() {
        val set = row.sided(SideResult(25.0, 10, 0), SideResult(30.0, 8, 0))
        assertEquals(25.0, set.weightLb)
        assertEquals(8, set.reps)
        assertEquals(0, set.seconds)
        assertTrue(set.sidesAgree)
    }

    @Test fun `a set without sides agrees and one whose values moved does not`() {
        assertTrue(row.sidesAgree)
        assertFalse(split.copy(reps = 9).sidesAgree)
    }

    @Test fun `a shared value moves on both sides`() {
        assertEquals(Sides(SideResult(30.0, 10, 0), SideResult(30.0, 8, 0)),
            split.sidesAfter(split.copy(weightLb = 30.0)))
    }

    @Test fun `a change to a value the sides differ on drops them`() {
        assertNull(split.sidesAfter(split.copy(reps = 9)))
    }

    @Test fun `unchanged values keep the sides`() {
        assertEquals(split.sides, split.sidesAfter(split.copy(done = true)))
    }

    @Test fun `negative or non finite side values are not valid`() {
        val valid = SideResult(0.0, 0, 0)
        assertTrue(Sides(valid, valid).valid)
        for (bad in listOf(valid.copy(weightLb = -1.0), valid.copy(weightLb = Double.NaN),
            valid.copy(weightLb = Double.POSITIVE_INFINITY), valid.copy(reps = -1), valid.copy(seconds = -1))) {
            assertFalse(Sides(bad, valid).valid)
            assertFalse(Sides(valid, bad).valid)
        }
    }

}
