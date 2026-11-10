package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals

class DaySeedsTest {
    @Test
    fun `authored seeds and previews use the prescription even for main or unknown catalog entries`() {
        val prescription = Prescription(PrescribedTarget.LoadReps(37.5, 8), eachSide = true)
        val slots = listOf("bb_back_squat", "unknown").mapIndexed { index, exerciseId ->
            ProgramSlot(index + 1L, index, ProgramExercise(exerciseId, targetSets = 2, prescription = prescription))
        }
        val expected = List(2) { LoggedSet(37.5, 8, SetKind.WORK) }
        slots.forEach { assertEquals(expected, DaySeeds.previewMain(it, LifterConfig(), ExerciseCatalog.CODE_ONLY)) }
    }

}
