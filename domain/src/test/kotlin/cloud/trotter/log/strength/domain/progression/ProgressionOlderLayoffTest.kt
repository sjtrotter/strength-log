package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressionOlderLayoffTest {
    private fun squat() = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 275.0, 5, 1, WeightUnit.LB,
        listOf(Equipment.BARBELL, Equipment.RACK), Scheme.STRENGTH)
    private fun rule() = SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 2, scheme = Scheme.STRENGTH)
    private fun ctx(day: Long) = Progression.Context(WeightUnit.LB, setOf(Equipment.BARBELL, Equipment.RACK),
        LoadSteps.defaults(WeightUnit.LB), day)
    private fun opening(day: Long, older: Boolean, exposures: Int = 0) = Progression.atStart(
        rule().copy(state = SlotState(exposures = exposures)), squat(), 1000, 7, ctx(day), olderAdult = older)

    @Test fun `case 23 an older trained lifter's eighteen extra days scale with fewer sets`() {
        assertEquals(7, Progression.expectedRecurrenceDays(2, 2, 1))
        for (older in listOf(false, true)) {
            val normal = opening(1010, older)
            assertEquals(LayoffKind.None, normal.layoff)
            assertEquals(squat(), normal.rows)
        }
        val older = opening(1025, true, exposures = 16)
        // audit 2026-11-13, row 11: 1025-1000-7 = 18; 275*0.85 = 233.75 -> 235 lb;
        // ceil(3*2/3) = 2 working sets. Sixteen exposures uses the trained 65+ band without calibration.
        assertEquals(LayoffKind.Scaled(0.85), older.layoff)
        assertEquals(235.0, WorkingRows.workingWeight(older.rows))
        assertNull(older.ruleOverride)
        assertEquals(2, WorkingRows.workSetIndices(older.rows).size)
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

    @Test fun `fifteen versus sixteen older exposures at twenty days past recurrence`() {
        // audit 2026-11-13, row 11: gap 27 - recurrence 7 = 20 extra days.
        // Under 16: 275*0.75 = 206.25 -> 205; from 16: 275*0.85 = 233.75 -> 235, ceil(3*2/3) = 2.
        val recent = opening(1027, true, exposures = 15)
        assertEquals(LayoffKind.Recalibrate, recent.layoff)
        assertEquals(205.0, WorkingRows.workingWeight(recent.rows))
        assertEquals(3, WorkingRows.workSetIndices(recent.rows).size)
        assertTrue(recent.ruleOverride!!.state.calibrating)
        val trained = opening(1027, true, exposures = 16)
        assertEquals(LayoffKind.Scaled(0.85), trained.layoff)
        assertEquals(235.0, WorkingRows.workingWeight(trained.rows))
        assertEquals(2, WorkingRows.workSetIndices(trained.rows).size)
        assertNull(trained.ruleOverride)
    }

    @Test fun `trained older layoff bands use the recurrence clock through forty three days`() {
        for ((extra, expected) in listOf(3 to LayoffKind.None, 4 to LayoffKind.Hold, 7 to LayoffKind.Hold,
            8 to LayoffKind.Scaled(0.95), 14 to LayoffKind.Scaled(0.95), 15 to LayoffKind.Scaled(0.85),
            42 to LayoffKind.Scaled(0.85), 43 to LayoffKind.Recalibrate)) {
            assertEquals(expected, opening(1007L + extra, true, exposures = 16).layoff)
        }
        for (exposures in listOf(15, 16)) {
            val opened = opening(1050, true, exposures)
            assertEquals(LayoffKind.Recalibrate, opened.layoff)
            assertEquals(205.0, WorkingRows.workingWeight(opened.rows))
            assertTrue(opened.ruleOverride!!.state.calibrating)
        }
    }

    @Test fun `New lifters below sixty five keep recalibration regardless of exposures`() {
        for (exposures in listOf(15, 16)) {
            val input = rule().copy(state = SlotState(exposures = exposures))
            val opened = Progression.atStart(input, squat(), 1000, 7, ctx(1027), newLifter = true)
            assertEquals(LayoffKind.Recalibrate, opened.layoff)
            assertEquals(205.0, WorkingRows.workingWeight(opened.rows))
            assertTrue(opened.ruleOverride!!.state.calibrating)
        }
    }
}
