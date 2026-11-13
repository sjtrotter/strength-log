package cloud.trotter.log.strength.domain.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class KeepScreenOnTest {
    @Test fun `each mode holds only its workout and timer combinations`() {
        data class Row(val mode: KeepScreenOn, val workout: Boolean, val timer: Boolean, val expected: Boolean)
        val table = listOf(
            Row(KeepScreenOn.OFF, false, false, false),
            Row(KeepScreenOn.OFF, false, true, false),
            Row(KeepScreenOn.OFF, true, false, false),
            Row(KeepScreenOn.OFF, true, true, false),
            Row(KeepScreenOn.WHILE_TIMER, false, false, false),
            Row(KeepScreenOn.WHILE_TIMER, false, true, true),
            Row(KeepScreenOn.WHILE_TIMER, true, false, false),
            Row(KeepScreenOn.WHILE_TIMER, true, true, true),
            Row(KeepScreenOn.DURING_WORKOUTS, false, false, false),
            Row(KeepScreenOn.DURING_WORKOUTS, false, true, true),
            Row(KeepScreenOn.DURING_WORKOUTS, true, false, true),
            Row(KeepScreenOn.DURING_WORKOUTS, true, true, true),
        )
        for ((mode, workout, timer, expected) in table) {
            assertEquals(expected, mode.holds(workout, timer), "$mode workout=$workout timer=$timer")
        }
    }

    @Test fun `legacy true means workouts and false means timer`() {
        assertEquals(KeepScreenOn.DURING_WORKOUTS, KeepScreenOn.fromLegacy(true))
        assertEquals(KeepScreenOn.WHILE_TIMER, KeepScreenOn.fromLegacy(false))
    }
}
