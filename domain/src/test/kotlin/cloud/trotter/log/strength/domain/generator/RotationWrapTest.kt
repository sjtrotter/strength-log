package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotationWrapTest {
    @Test fun `finishing the last training day as up next wraps`() {
        val p = Program(listOf("A", "B", "C", "R", "D").map {
            ProgramDay(it, it, "", emptyList(), kind = if (it == "R") ProgramDayKind.REST else ProgramDayKind.STRENGTH)
        })
        assertTrue(Rotation.wraps(p, "D", "D"))
        assertTrue(Rotation.wraps(p, "R", "D"))
        assertFalse(Rotation.wraps(p, "B", "D"))
        assertFalse(Rotation.wraps(p, "C", "C"))
        assertTrue(Rotation.wraps(Program(p.days.take(1)), "A", "A"))
        val extras = Program(listOf(p.days.first()) + listOf("C1", "C2").map {
            ProgramDay(it, it, "", emptyList(), kind = ProgramDayKind.CARDIO)
        })
        assertFalse(Rotation.wraps(extras, "C1", "C1"))
        assertFalse(Rotation.wraps(extras, "A", "C1"))
    }
}
