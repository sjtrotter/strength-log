package cloud.trotter.log.strength.ui.plan

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanScreenBuilderTest {
    private val config = LifterConfig()
    private val catalog = ExerciseCatalog.CODE_ONLY

    @Test
    fun days_preserve_rotation_order_count_slots_and_rounds_and_mark_one_next_day() {
        val program = Program(
            listOf(
                day("A", "Full body", 3, 4),
                day("B", "Lower body", 2, 5, 3),
                day("C", "Upper body", 4),
            ),
        )

        val rows = PlanScreenBuilder.days(program, nextDayId = "B", config = config, catalog = catalog)

        assertEquals(listOf("A", "B", "C"), rows.map { it.dayId })
        assertEquals(listOf(0, 1, 2), rows.map { it.dayIndex })
        assertEquals(listOf(2, 3, 1), rows.map { it.liftCount })
        assertEquals(listOf(7, 10, 4), rows.map { it.setCount })
        assertEquals(listOf(false, true, false), rows.map { it.isNext })
    }

    @Test
    fun days_handle_a_six_day_program_with_indices_zero_through_five() {
        val program = Program(
            (0..5).map { index ->
                day(('A'.code + index).toChar().toString(), "Day ${index + 1}", index + 1)
            },
        )

        val rows = PlanScreenBuilder.days(program, nextDayId = "F", config = config, catalog = catalog)

        assertEquals((0..5).toList(), rows.map { it.dayIndex })
        assertEquals(6, rows.size)
        assertEquals(1, rows.count { it.isNext })
        assertTrue(rows.last().isNext)
        assertFalse(rows.first().isNext)
    }

    @Test
    fun weightedMain_countsTheSeededRampTopAndBackOffRows() {
        val program = Program(
            listOf(
                ProgramDay(
                    id = "A",
                    title = "Day A",
                    emphasisLine = "",
                    exercises = listOf(
                        ProgramExercise("bb_back_squat", isMain = true, targetSets = 3),
                        ProgramExercise("ez_curl", targetSets = 4),
                    ),
                ),
            ),
        )

        val row = PlanScreenBuilder.days(
            program,
            nextDayId = "A",
            config = config,
            catalog = catalog,
        ).single()

        assertEquals(10, row.setCount)
        assertTrue(row.setCount > program.strengthDays.single().exercises.sumOf { it.targetSets })
    }

    private fun day(id: String, title: String, vararg rounds: Int) = ProgramDay(
        id = id,
        title = title,
        emphasisLine = "",
        exercises = rounds.mapIndexed { index, count ->
            ProgramExercise(exerciseId = "exercise-$id-$index", targetSets = count)
        },
    )
}
