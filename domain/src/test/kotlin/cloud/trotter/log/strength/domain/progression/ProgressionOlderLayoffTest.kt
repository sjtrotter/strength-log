package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressionOlderLayoffTest {
    private fun squat() = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 275.0, 5, 1, WeightUnit.LB,
        listOf(Equipment.BARBELL, Equipment.RACK), Scheme.STRENGTH)
    private fun rule() = SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 2, scheme = Scheme.STRENGTH)
    private fun ctx(day: Long) = Progression.Context(WeightUnit.LB, setOf(Equipment.BARBELL, Equipment.RACK),
        LoadSteps.defaults(WeightUnit.LB), day)
    private fun opening(day: Long, older: Boolean) = Progression.atStart(rule(), squat(), 1000, 7, ctx(day), olderOrNew = older)

    @Test fun `case 23 an older lifter's long layoff recalibrates`() {
        assertEquals(7, Progression.expectedRecurrenceDays(2, 2, 1))
        for (older in listOf(false, true)) {
            val normal = opening(1010, older)
            assertEquals(LayoffKind.None, normal.layoff)
            assertEquals(squat(), normal.rows)
        }
        val older = opening(1025, true)
        assertEquals(LayoffKind.Recalibrate, older.layoff)
        assertEquals(205.0, WorkingRows.workingWeight(older.rows))
        assertTrue(older.ruleOverride!!.state.calibrating)
        val younger = opening(1025, false)
        assertEquals(LayoffKind.Scaled(0.85), younger.layoff)
        assertEquals(235.0, WorkingRows.workingWeight(younger.rows))
        assertEquals(2, WorkingRows.workSetIndices(younger.rows).size)
    }

    @Test fun `the older row starts past fourteen days`() {
        for (layoff in listOf(4L, 7L)) assertEquals(LayoffKind.Hold, opening(1007 + layoff, true).layoff)
        for (layoff in listOf(8L, 14L)) {
            val scaled = opening(1007 + layoff, true)
            assertEquals(LayoffKind.Scaled(0.95), scaled.layoff)
            assertEquals(260.0, WorkingRows.workingWeight(scaled.rows))
        }
        assertEquals(LayoffKind.Recalibrate, opening(1022, true).layoff)
        assertEquals(LayoffKind.Scaled(0.85), opening(1022, false).layoff)
    }
}
