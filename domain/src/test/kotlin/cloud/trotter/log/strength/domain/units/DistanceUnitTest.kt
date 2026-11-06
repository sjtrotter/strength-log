package cloud.trotter.log.strength.domain.units

import cloud.trotter.log.strength.domain.model.CardioActivity
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DistanceUnitTest {
    @Test fun `distance formatting uses display precision and trims zeros`() {
        assertEquals("2 mi", DistanceUnit.MI.format(3219))
        assertEquals("0.25 mi", DistanceUnit.MI.format(400))
        assertEquals("8.4 mi", DistanceUnit.MI.format(13_518))
        assertEquals("5 km", DistanceUnit.KM.format(5000))
        assertEquals("1,200 m", DistanceUnit.M.format(1200))
        assertEquals("3.1", DistanceUnit.MI.inputText(4989))
        assertEquals("5", DistanceUnit.KM.inputText(5000))
        assertEquals("1200", DistanceUnit.M.inputText(1200))
        assertEquals("1.01 km", DistanceUnit.KM.format(1005))
    }

    @Test fun `typed distances convert to whole metres within the measure range`() {
        assertEquals(4989, DistanceUnit.MI.toMetres(3.1))
        assertEquals(400, DistanceUnit.KM.toMetres(0.4))
        assertNull(DistanceUnit.MI.toMetres(0.0))
        assertNull(DistanceUnit.KM.toMetres(250.0))
        assertEquals(1, DistanceUnit.M.toMetres(1.0))
        assertEquals(200_000, DistanceUnit.KM.toMetres(200.0))
        for (unit in DistanceUnit.entries) {
            assertNull(unit.toMetres(-1.0))
            assertNull(unit.toMetres(Double.NaN))
            assertNull(unit.toMetres(Double.POSITIVE_INFINITY))
            assertNull(unit.toMetres(Double.MAX_VALUE))
        }
    }

    @Test fun `track and rower keep metres and other activities use the setting`() {
        assertEquals(DistanceUnit.M, DistanceUnit.forActivity(DistanceUnit.MI, CardioActivity.TRACK_RUN))
        assertEquals(DistanceUnit.M, DistanceUnit.forActivity(DistanceUnit.KM, CardioActivity.ROWER))
        assertEquals(DistanceUnit.KM, DistanceUnit.forActivity(DistanceUnit.KM, CardioActivity.ROAD_RUN))
        assertEquals(DistanceUnit.MI, DistanceUnit.forActivity(DistanceUnit.MI, null))
        assertEquals(DistanceUnit.KM, DistanceUnit.suggestedFor(WeightUnit.KG))
        assertEquals(DistanceUnit.MI, DistanceUnit.suggestedFor(WeightUnit.LB))
    }

    @Test fun `formatting stays stable under a different locale`() {
        val previous = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE)
            assertEquals("0.25 mi", DistanceUnit.MI.format(400))
            assertEquals("1,200 m", DistanceUnit.M.format(1200))
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous)
        }
    }
}
