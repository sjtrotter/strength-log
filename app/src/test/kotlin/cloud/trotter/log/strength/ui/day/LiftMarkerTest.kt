package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiftMarkerTest {
    private val rule = SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 1, scheme = Scheme.STRENGTH)

    @Test fun calibratingRuleShowsEstimate() {
        assertEquals(LiftMarker.ESTIMATE, LiftMarker.of(rule.copy(state = SlotState(calibrating = true)), null, 0))
    }

    @Test fun markerUsesOpeningAndLightCounterWithEstimateFirst() {
        assertEquals(LiftMarker.LIGHT_WEEK, LiftMarker.of(rule, OpeningDto(light = true), 2))
        assertEquals(LiftMarker.LIGHTER_TODAY, LiftMarker.of(rule, OpeningDto(light = true), 0))
        val scaled = OpeningDto(OpeningDto.SCALED, 0.85)
        assertEquals(LiftMarker.AFTER_BREAK, LiftMarker.of(rule, scaled, 0))
        assertEquals(LiftMarker.ESTIMATE, LiftMarker.of(rule.copy(state = SlotState(calibrating = true)), scaled, 0))
        assertEquals(LiftMarker.ESTIMATE, LiftMarker.of(rule, OpeningDto(OpeningDto.RECALIBRATE, light = true), 2))
        assertEquals(LiftMarker.LIGHT_WEEK, LiftMarker.of(rule, scaled.copy(light = true), 2))
        assertNull(LiftMarker.of(rule, null, 2))
        assertNull(LiftMarker.of(rule, OpeningDto(OpeningDto.HOLD), 0))
    }
}
