package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** [SetFormatter] is the SSOT for rendering a performed set per tracking type. */
class SetFormatterTest {

    @Test
    fun `explicit measurements include the unit for every tracking type in lb and kg`() {
        for (unit in WeightUnit.entries) {
            val label = unit.name.lowercase()
            val weightLb = unit.toLb(102.5)
            assertEquals("102.5 $label × 12", SetFormatter.display(TrackingType.WEIGHTED, weightLb, 12, 0, unit))
            assertEquals("20 reps", SetFormatter.display(TrackingType.REPS, weightLb, 20, 0, unit))
            assertEquals("1 rep", SetFormatter.display(TrackingType.REPS, 0.0, 1, 0, unit))
            assertEquals("45 s", SetFormatter.display(TrackingType.TIMED, 0.0, 0, 45, unit))
            assertEquals("90 s at 102.5 $label", SetFormatter.display(TrackingType.TIMED, weightLb, 0, 90, unit))
        }
    }

    @Test
    fun `weighted renders weight by reps, unchanged from the old w by r form`() {
        assertEquals("225×5", SetFormatter.summary(TrackingType.WEIGHTED, 225.0, 5, 0, WeightUnit.LB))
    }

    @Test
    fun `reps ignores the zero weight and never reads as 0 lb`() {
        assertEquals("×12", SetFormatter.summary(TrackingType.REPS, 0.0, 12, 0, WeightUnit.LB))
    }

    @Test
    fun `timed renders the hold, not a rep count`() {
        assertEquals("45s", SetFormatter.summary(TrackingType.TIMED, 0.0, 0, 45, WeightUnit.LB))
    }

    @Test
    fun `timed with added load surfaces the weight`() {
        assertEquals("45s +25", SetFormatter.summary(TrackingType.TIMED, 25.0, 0, 45, WeightUnit.LB))
    }

    @Test
    fun `timed crosses the 90 second threshold into m colon ss, same as the GOAL chip`() {
        assertEquals("1:30", SetFormatter.summary(TrackingType.TIMED, 0.0, 0, 90, WeightUnit.LB))
    }

    // --- summaryOfValues: value-driven, for history/Best/last-time surfaces ----

    @Test
    fun `summaryOfValues reads a hold from a positive seconds value alone`() {
        assertEquals("45s", SetFormatter.summaryOfValues(0.0, 0, 45, WeightUnit.LB))
        assertEquals("45s +25", SetFormatter.summaryOfValues(25.0, 0, 45, WeightUnit.LB))
    }

    @Test
    fun `summaryOfValues reads a zero-weight positive-rep row as reps`() {
        assertEquals("×12", SetFormatter.summaryOfValues(0.0, 12, 0, WeightUnit.LB))
    }

    @Test
    fun `summaryOfValues falls back to weight by reps otherwise`() {
        assertEquals("225×5", SetFormatter.summaryOfValues(225.0, 5, 0, WeightUnit.LB))
    }

    @Test
    fun `summaryOfValues renders a legacy reps-shaped TIMED history row as reps, never 0s`() {
        // A plank logged before its reclassification to TIMED: the only field
        // the UI offered at the time was reps, so the user's 45-second hold sits
        // in `reps` with `seconds` still 0 (the fixup only touches live
        // exercise_log rows, never session_set history). Rendering by value must
        // read this as its logged rep count, not manufacture "0s".
        assertEquals("×45", SetFormatter.summaryOfValues(0.0, 45, 0, WeightUnit.LB))
    }
}
