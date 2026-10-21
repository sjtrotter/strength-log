package cloud.trotter.log.strength.domain

import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class RotationTest {
    private val program = Program(listOf(
        ProgramDay("A", "Strength A", "", emptyList(), null),
        ProgramDay("B", "Cardio B", "", emptyList(), null, kind = ProgramDayKind.CARDIO),
        ProgramDay("C", "Strength C", "", emptyList(), null),
    ))

    @Test fun authoredPlansRotateThroughEveryDay() {
        assertEquals("B", Rotation.nextFor(program, "A", authored = true))
        assertEquals("C", Rotation.nextFor(program, "B", authored = true))
        assertEquals("A", Rotation.nextFor(program, "C", authored = true))
    }

    @Test fun generatedPlansKeepTheExistingStrengthRotation() {
        program.strengthDays.forEach { day ->
            assertEquals(Rotation.next(program, day.id), Rotation.nextFor(program, day.id, authored = false))
        }
        assertNull(Rotation.nextFor(program, "B", authored = false))
    }

    @Test fun removedAndEmptyDaysHaveNoSuccessor() {
        assertNull(Rotation.nextFor(program, "removed", authored = true))
        assertNull(Rotation.nextFor(Program(emptyList()), "A", authored = true))
        assertNull(Rotation.nextFor(Program(listOf(program.days[1])), "B", authored = false))
    }
}
