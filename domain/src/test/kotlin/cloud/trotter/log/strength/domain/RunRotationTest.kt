package cloud.trotter.log.strength.domain

import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.CardioRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunRotationTest {
    private fun time(seconds: Int, role: CardioRole = CardioRole.WORK) = CardioSetSpec(role, Measure.Time(seconds))
    private fun distance(metres: Int) = CardioSetSpec(CardioRole.WORK, Measure.Distance(metres))
    private fun block(vararg sets: CardioSetSpec) = CardioBlock(CardioActivity.ROAD_RUN, sets.toList())

    @Test
    fun `case 24 run days with strength and rest rotate through every entry`() {
        val exercises = listOf(ProgramExercise("bb_back_squat"))
        val quality = CardioBlock(CardioActivity.ROAD_RUN, buildList {
            repeat(4) { index ->
                if (index > 0) add(time(90, CardioRole.RECOVERY))
                add(distance(1000).copy(hard = true))
            }
        })
        val program = Program(listOf(
            ProgramDay("A", "Easy", "", exercises, kind = ProgramDayKind.CARDIO,
                cardioBlock = block(time(1800)), cardioRule = CardioRule(3)),
            ProgramDay("B", "Quality", "", exercises, kind = ProgramDayKind.CARDIO, cardioBlock = quality),
            ProgramDay("C", "Long", "", emptyList(), kind = ProgramDayKind.CARDIO, cardioBlock = block(distance(12000))),
            ProgramDay("D", "Rest", "", emptyList(), kind = ProgramDayKind.REST),
        ))
        assertEquals(listOf("A", "B", "C", "D"), program.rotationDays.map { it.id })
        for ((from, to) in listOf("A" to "B", "B" to "C", "C" to "D")) {
            assertEquals(to, Rotation.upNextAfter(program, from, from))
        }
        assertEquals("A", Rotation.trainingDayFrom(program, "D"))
        assertEquals("A", program.dayToShow("D")?.id)
        assertEquals("B", Rotation.upNextAfter(program, "D", "A"))
        assertEquals(CardioRule(3), program.days.first().cardioRule)
        assertNull(ProgramDay("E", "Lift", "", exercises).cardioRule)
    }
}
