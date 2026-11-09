package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetEdit
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemeTest {
    private fun shape(top: Double, scheme: Scheme = Scheme.STRENGTH) =
        SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, top, 5, 1, WeightUnit.LB, listOf(Equipment.BARBELL, Equipment.RACK), scheme)
    private fun row(set: LoggedSet) = Triple(set.weightLb, set.reps, set.kind)

    @Test
    fun `prototype equals today's default rows`() {
        val default = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 235.0, 5, 1)
        assertEquals(default, SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 235.0, 5, 1, scheme = Scheme.PROTOTYPE))
        assertEquals(listOf(130.0, 165.0, 190.0, 210.0, 235.0, 175.0), default.map { it.weightLb })
        assertEquals(listOf(5, 5, 5, 3, 5, 8), default.map { it.reps })
    }

    @Test
    fun `case 2 Strength shapes R's squat`() {
        assertEquals(listOf(
            Triple(165.0, 5, SetKind.RAMP), Triple(220.0, 3, SetKind.RAMP), Triple(275.0, 5, SetKind.TOP),
            Triple(250.0, 5, SetKind.BACKOFF), Triple(250.0, 5, SetKind.BACKOFF),
        ), shape(275.0).map(::row))
    }

    @Test
    fun `case 3 Strength shapes R's bench`() {
        assertEquals(listOf(
            Triple(110.0, 5, SetKind.RAMP), Triple(150.0, 3, SetKind.RAMP), Triple(185.0, 5, SetKind.TOP),
            Triple(165.0, 5, SetKind.BACKOFF), Triple(165.0, 5, SetKind.BACKOFF),
        ), shape(185.0).map(::row))
    }

    @Test
    fun `case 16 cascades follow the chosen scheme`() {
        assertEquals(listOf(170.0, 230.0, 285.0, 255.0, 255.0),
            SetEditor.editWeight(shape(275.0), 2, 285.0, scheme = Scheme.STRENGTH).map { it.weightLb })
        assertEquals(listOf(135.0, 170.0, 195.0, 220.0, 245.0, 185.0),
            SetEditor.editWeight(shape(235.0, Scheme.PROTOTYPE), 4, 245.0, scheme = Scheme.PROTOTYPE).map { it.weightLb })
        assertEquals(SetEditor.editWeight(shape(275.0), 2, 285.0, scheme = Scheme.STRENGTH),
            SetEditor.edit(shape(275.0), 2, SetEdit(weightLb = 285.0), scheme = Scheme.STRENGTH))
    }

    @Test
    fun `round5 keeps the old floor and half up values`() {
        assertEquals(5.0, Scheme.round5(2.0), 1e-9)
        assertEquals(250.0, Scheme.round5(247.5), 1e-9)
        assertEquals(170.0, Scheme.round5(171.0), 1e-9)
    }

    @Test
    fun `a ramp beyond the scheme keeps its weight`() {
        val rows = listOf(LoggedSet(100.0, 5, SetKind.RAMP)) + shape(275.0)
        val after = SetEditor.editWeight(rows, 3, 285.0, scheme = Scheme.STRENGTH)
        assertEquals(rows[2], after[2])
    }
}
