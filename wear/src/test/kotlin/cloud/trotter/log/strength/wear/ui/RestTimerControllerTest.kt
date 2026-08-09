package cloud.trotter.log.strength.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RestTimerControllerTest {

    @Test
    fun `arm schedules at the deadline`() {
        val alarm = RecordingAlarmScheduler()
        val controller = controller(alarm)

        controller.arm(deadlineMillis = 5_000L, totalSeconds = 5)

        assertEquals(listOf(5_000L), alarm.deadlines)
        assertEquals(RestTimerController.ActiveRest(5_000L, 5), controller.activeRest)
    }

    @Test
    fun `re-arm replaces the single outstanding alarm`() {
        val alarm = RecordingAlarmScheduler()
        val controller = controller(alarm)

        controller.arm(deadlineMillis = 5_000L, totalSeconds = 5)
        controller.arm(deadlineMillis = 9_000L, totalSeconds = 9)

        assertEquals(2, alarm.cancelCount)
        assertEquals(listOf(5_000L, 9_000L), alarm.deadlines)
        assertEquals(RestTimerController.ActiveRest(9_000L, 9), controller.activeRest)
    }

    @Test
    fun `disarm cancels without buzzing`() {
        val alarm = RecordingAlarmScheduler()
        var buzzes = 0
        val controller = controller(alarm) { buzzes++ }
        controller.arm(deadlineMillis = 5_000L, totalSeconds = 5)

        controller.disarm()
        alarm.fire()

        assertEquals(2, alarm.cancelCount)
        assertEquals(0, buzzes)
        assertNull(controller.activeRest)
    }

    @Test
    fun `alarm fire buzzes once`() {
        val alarm = RecordingAlarmScheduler()
        var buzzes = 0
        val controller = controller(alarm) { buzzes++ }
        controller.arm(deadlineMillis = 5_000L, totalSeconds = 5)

        alarm.fire()
        alarm.fire()

        assertEquals(1, buzzes)
        assertNull(controller.activeRest)
    }

    @Test
    fun `timed hold uses the same alarm machinery`() {
        val alarm = RecordingAlarmScheduler()
        var buzzes = 0
        val controller = controller(alarm) { buzzes++ }
        val goalDeadline = RestTimer.deadlineFrom(nowElapsedMillis = 1_000L, restAfterSeconds = 30)

        controller.arm(goalDeadline, totalSeconds = 30)
        alarm.fire()

        assertEquals(listOf(31_000L), alarm.deadlines)
        assertEquals(1, buzzes)
    }

    private fun controller(
        alarm: RecordingAlarmScheduler,
        buzz: () -> Unit = {},
    ) = RestTimerController(
        alarmScheduler = alarm,
        elapsedRealtime = { 1_000L },
        buzz = buzz,
    )

    private class RecordingAlarmScheduler : RestAlarmScheduler {
        val deadlines = mutableListOf<Long>()
        var cancelCount = 0
        private var callback: (() -> Unit)? = null

        override fun schedule(deadlineMillis: Long, onAlarm: () -> Unit) {
            deadlines += deadlineMillis
            callback = onAlarm
        }

        override fun cancel() {
            cancelCount++
            callback = null
        }

        fun fire() {
            callback?.also { callback = null }?.invoke()
        }
    }
}
