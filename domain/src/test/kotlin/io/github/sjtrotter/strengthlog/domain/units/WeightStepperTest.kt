package io.github.sjtrotter.strengthlog.domain.units

import kotlin.test.Test
import kotlin.test.assertEquals

class WeightStepperTest {

    @Test
    fun `lb increment is light at and below the 20 lb threshold`() {
        assertEquals(2.5, WeightStepper.increment(20.0, WeightUnit.LB))
        assertEquals(2.5, WeightStepper.increment(5.0, WeightUnit.LB))
    }

    @Test
    fun `lb increment is standard above the 20 lb threshold`() {
        assertEquals(5.0, WeightStepper.increment(20.01, WeightUnit.LB))
        assertEquals(5.0, WeightStepper.increment(135.0, WeightUnit.LB))
    }

    @Test
    fun `kg increment is light at and below the 9 kg threshold`() {
        assertEquals(1.25, WeightStepper.increment(9.0, WeightUnit.KG))
        assertEquals(1.25, WeightStepper.increment(4.0, WeightUnit.KG))
    }

    @Test
    fun `kg increment is standard above the 9 kg threshold`() {
        assertEquals(2.5, WeightStepper.increment(9.01, WeightUnit.KG))
        assertEquals(2.5, WeightStepper.increment(60.0, WeightUnit.KG))
    }

    @Test
    fun `rounds lb to the nearest 5 above the light threshold`() {
        assertEquals(135.0, WeightStepper.round(133.0, WeightUnit.LB))
        assertEquals(140.0, WeightStepper.round(137.6, WeightUnit.LB))
    }

    @Test
    fun `rounds lb to the nearest 2point5 at or below the light threshold`() {
        assertEquals(17.5, WeightStepper.round(18.0, WeightUnit.LB))
        assertEquals(20.0, WeightStepper.round(20.0, WeightUnit.LB))
    }

    @Test
    fun `rounds kg to the nearest 2point5 above the light threshold`() {
        assertEquals(60.0, WeightStepper.round(59.0, WeightUnit.KG))
        assertEquals(27.5, WeightStepper.round(26.4, WeightUnit.KG))
    }

    @Test
    fun `rounds kg to the nearest 1point25 at or below the light threshold`() {
        assertEquals(6.25, WeightStepper.round(6.5, WeightUnit.KG))
        assertEquals(8.75, WeightStepper.round(9.0, WeightUnit.KG))
    }

    @Test
    fun `rounding never goes below one increment`() {
        assertEquals(2.5, WeightStepper.round(0.0, WeightUnit.LB))
        assertEquals(2.5, WeightStepper.round(1.0, WeightUnit.LB))
        assertEquals(1.25, WeightStepper.round(0.0, WeightUnit.KG))
        assertEquals(1.25, WeightStepper.round(0.5, WeightUnit.KG))
    }

    @Test
    fun `formats whole numbers without a decimal`() {
        assertEquals("60", WeightStepper.format(60.0))
        assertEquals("135", WeightStepper.format(135.0))
    }

    @Test
    fun `formats halves and quarters without trailing noise`() {
        assertEquals("27.5", WeightStepper.format(27.5))
        assertEquals("6.25", WeightStepper.format(6.25))
        assertEquals("8.75", WeightStepper.format(8.75))
    }
}
