package cloud.trotter.log.strength.domain

import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class RotationTest {
    private val program = Program(listOf("A", "B", "C").map { ProgramDay(it, "Day $it", "", emptyList()) })

    @Test fun finishingSuggestedDayAdvancesToItsSuccessor() {
        assertEquals("C", Rotation.upNextAfter(program, "B", "B"))
    }

    @Test fun finishingOutOfOrderKeepsSuggestedDay() {
        assertEquals("B", Rotation.upNextAfter(program, "B", "C"))
    }

    @Test fun finishingWithStaleSuggestionAdvancesFromFinishedDay() {
        assertEquals("B", Rotation.upNextAfter(program, "removed", "A"))
    }

    @Test fun finishingWithNoSuggestionAdvancesFromFinishedDay() {
        assertEquals("B", Rotation.upNextAfter(program, null, "A"))
    }

    @Test fun finishingLastSuggestedDayWrapsToFirst() {
        assertEquals("A", Rotation.upNextAfter(program, "C", "C"))
    }

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

    @Test fun mixedTrainingDaysVisitRunLiftRunAndWrap() {
        for (kind in ProgramDayKind.entries) {
            val program = mixedProgram(kind)
            assertEquals("B", Rotation.nextFor(program, "A"))
            assertEquals("C", Rotation.nextFor(program, "B"))
            assertEquals("A", Rotation.nextFor(program, "C"))
            assertEquals("B", Rotation.upNextAfter(program, "A", "A"))
            assertEquals("C", Rotation.upNextAfter(program, "B", "B"))
            assertEquals("A", Rotation.upNextAfter(program, "C", "C"))
        }
    }

    @Test fun mixedRotationSkipsGeneratedExtrasInPlanOrder() {
        val days = mixedProgram(ProgramDayKind.CARDIO).days
        val extras = ProgramGenerator.generate(WizardAnswers(
            cardio = CardioPrefs(placement = CardioPlacement.SEPARATE_DAYS),
        )).cardioDays
        val program = Program(listOf(extras[0], days[0], days[1], extras[1], days[2]))

        assertEquals(listOf("A", "B", "C"), program.rotationDays.map { it.id })
        assertEquals("B", Rotation.nextFor(program, "A"))
        assertEquals("C", Rotation.nextFor(program, "B"))
        assertEquals("A", Rotation.nextFor(program, "C"))
        extras.forEach { assertNull(Rotation.nextFor(program, it.id)) }
        assertEquals("B", Rotation.upNextAfter(program, "B", "C1"))
    }

    @Test fun finishingMixedDaysOutOfOrderKeepsSuggestion() {
        val program = mixedProgram(ProgramDayKind.CARDIO)
        assertEquals("B", Rotation.upNextAfter(program, "B", "C"))
        assertEquals("C", Rotation.upNextAfter(program, "C", "B"))
    }

    @Test fun cardioOnlyTrainingDaysRotateAndWrap() {
        val program = Program(mixedProgram(ProgramDayKind.CARDIO).days.filter { it.exercises.isEmpty() })
        assertEquals("C", Rotation.nextFor(program, "A"))
        assertEquals("A", Rotation.nextFor(program, "C"))
        assertEquals("A", Rotation.nextFor(Program(program.days.take(1)), "A"))
    }

    @Test fun liftingOnlyGeneratedPlanKeepsItsRotation() {
        val program = ProgramGenerator.generate(WizardAnswers(
            cardio = CardioPrefs(placement = CardioPlacement.NONE),
        ))
        assertEquals(listOf("A", "B", "C", "D"), program.rotationDays.map { it.id })
        assertEquals("B", Rotation.nextFor(program, "A"))
        assertEquals("C", Rotation.nextFor(program, "B"))
        assertEquals("D", Rotation.nextFor(program, "C"))
        assertEquals("A", Rotation.nextFor(program, "D"))
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

    private fun mixedProgram(cardioKind: ProgramDayKind): Program {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500))))
        return Program(listOf(
            ProgramDay("A", "Run", "", emptyList(), kind = cardioKind, cardioBlock = block),
            ProgramDay("B", "Lift", "", listOf(ProgramExercise("bb_back_squat"))),
            ProgramDay("C", "Run", "", emptyList(), kind = cardioKind, cardioBlock = block),
        ))
    }
}
