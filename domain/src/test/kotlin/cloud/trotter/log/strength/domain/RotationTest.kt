package cloud.trotter.log.strength.domain

import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class RotationTest {
    @Test fun authoredPlansKeepTheExistingEveryDayRotation() {
        val program = Program(listOf("A", "B", "C").map { ProgramDay(it, "Day $it", "", emptyList()) })
        program.days.forEachIndexed { index, day ->
            assertEquals(program.days[(index + 1) % program.days.size].id, Rotation.nextFor(program, day.id))
        }
    }

    @Test fun generatedPlansKeepTheExistingStrengthRotation() {
        val program = ProgramGenerator.generate(WizardAnswers(
            cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS),
        ))
        assertEquals(listOf("C1", "C2"), program.cardioDays.map { it.id })
        program.strengthDays.forEachIndexed { index, day ->
            assertEquals(program.strengthDays[(index + 1) % program.strengthDays.size].id,
                Rotation.nextFor(program, day.id))
        }
        program.cardioDays.forEach { assertNull(Rotation.nextFor(program, it.id)) }
    }

    @Test fun allCardioPlansRotateThroughTheirDays() {
        val program = Program(listOf("C1", "C2").map {
            ProgramDay(it, "Cardio $it", "", emptyList(), kind = ProgramDayKind.CARDIO)
        })
        assertEquals("C2", Rotation.nextFor(program, "C1"))
        assertEquals("C1", Rotation.nextFor(program, "C2"))
        assertEquals("C1", Rotation.nextFor(Program(program.days.take(1)), "C1"))
    }

    @Test fun removedAndEmptyDaysHaveNoSuccessor() {
        val program = Program(listOf(ProgramDay("A", "Day A", "", emptyList())))
        assertNull(Rotation.nextFor(program, "removed"))
        assertNull(Rotation.nextFor(Program(emptyList()), "A"))
    }
}
