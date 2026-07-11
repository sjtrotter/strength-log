package io.github.sjtrotter.strengthlog.domain.standards

import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** [SetFormatter] is the SSOT for rendering a performed set per tracking type. */
class SetFormatterTest {

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
}
