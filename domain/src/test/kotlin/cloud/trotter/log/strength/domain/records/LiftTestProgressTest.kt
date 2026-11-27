package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiftTestProgressTest {
    @Test fun `case 61 a weight goal is met by any non ramp set with the reps`() {
        fun set(weight: Double, reps: Int, kind: SetKind = SetKind.WORK, done: Boolean = true) =
            LoggedSet(weight, reps, kind, done = done)
        val sets = listOf(set(205.0, 5), set(225.0, 4), set(215.0, 6),
            set(240.0, 5, SetKind.RAMP), set(250.0, 5, done = false))
        assertEquals(215.0, LiftTestProgress.best(5, sets))
        assertEquals(225.0, LiftTestProgress.best(5, sets + set(225.0, 5)))
        assertEquals(230.0, LiftTestProgress.best(5, listOf(set(230.0, 5, SetKind.TOP), set(240.0, 3, SetKind.RAMP))))
        assertEquals(225.0, LiftTestProgress.best(5, listOf(set(230.0, 1, SetKind.TOP), set(225.0, 5, SetKind.BACKOFF))))
        assertEquals(225.0, LiftTestProgress.best(5, listOf(set(225.0, 5, SetKind.EXTRA))))
        assertNull(LiftTestProgress.best(5, emptyList()))
        assertNull(LiftTestProgress.best(5, listOf(set(0.0, 5))))
    }
}
