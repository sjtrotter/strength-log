package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class LoadStepsTest {
    private val lb = WeightUnit.LB
    private val steps = LoadSteps.defaults(lb)

    @Test
    fun `step family gives bars precedence over stacks and stacks over dumbbells`() {
        val custom = LoadSteps(2.5, 5.0, 10.0)
        for (bar in listOf(Equipment.BARBELL, Equipment.TRAP_BAR, Equipment.EZ_BAR)) {
            assertEquals(2.5, custom.stepLb(listOf(bar, Equipment.MACHINE, Equipment.DUMBBELL)), 1e-9)
        }
        for (stack in listOf(Equipment.MACHINE, Equipment.CABLE)) {
            assertEquals(10.0, custom.stepLb(listOf(stack, Equipment.DUMBBELL)), 1e-9)
        }
        for (equipment in listOf(emptyList(), listOf(Equipment.DUMBBELL), listOf(Equipment.KETTLEBELL), listOf(Equipment.BENCH))) {
            assertEquals(5.0, custom.stepLb(equipment), 1e-9)
        }
    }

    @Test
    fun `rounding uses the absolute step grid and half up ties`() {
        assertEquals(100.0, steps.nearest(97.5, emptyList(), lb), 1e-9)
        assertEquals(95.0, steps.down(97.5, emptyList(), lb), 1e-9)
        assertEquals(100.0, steps.up(97.5, emptyList(), lb), 1e-9)
        assertEquals(5.0, steps.nearest(-10.0, emptyList(), lb), 1e-9)
        assertEquals(10.0, steps.down(0.0, listOf(Equipment.CABLE), lb), 1e-9)
    }

    @Test
    fun `barbell and trap bar floor at the unit bar but EZ bar floors at one step`() {
        for (unit in WeightUnit.entries) {
            val grid = LoadSteps.defaults(unit)
            for (bar in listOf(Equipment.BARBELL, Equipment.TRAP_BAR)) {
                val expected = if (unit == lb) 45.0 else unit.toLb(20.0)
                assertEquals(expected, grid.nearest(0.0, listOf(bar), unit), 1e-9)
                assertEquals(expected, grid.down(0.0, listOf(bar), unit), 1e-9)
                assertEquals(expected, grid.up(0.0, listOf(bar), unit), 1e-9)
            }
            assertEquals(grid.barLb, grid.nearest(0.0, listOf(Equipment.EZ_BAR), unit), 1e-9)
        }
    }

    @Test
    fun `kg defaults and rounding keep the kg grid in canonical storage`() {
        val kg = WeightUnit.KG
        val grid = LoadSteps.defaults(kg)
        assertEquals(kg.toLb(2.5), grid.barLb, 1e-9)
        assertEquals(kg.toLb(2.0), grid.dumbbellLb, 1e-9)
        assertEquals(kg.toLb(5.0), grid.stackLb, 1e-9)
        assertEquals(102.5, kg.fromLb(grid.nearest(kg.toLb(102.0), listOf(Equipment.BARBELL), kg)), 1e-9)
        assertEquals(100.0, kg.fromLb(grid.down(kg.toLb(102.0), listOf(Equipment.BARBELL), kg)), 1e-9)
        assertEquals(104.0, kg.fromLb(grid.up(kg.toLb(103.0), listOf(Equipment.DUMBBELL), kg)), 1e-9)
    }
}
