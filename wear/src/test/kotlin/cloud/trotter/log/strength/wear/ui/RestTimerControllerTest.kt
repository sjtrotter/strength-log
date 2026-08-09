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
    fun `alarm fire invokes completion once`() {
        val alarm = RecordingAlarmScheduler()
        var completions = 0
        val controller = controller(alarm)
        controller.arm(5_000L, 5) { completions++ }

        alarm.fire()
        alarm.fire()

        assertEquals(1, completions)
    }

    @Test
    fun `re-arm and disarm invalidate a fired completion`() {
        val alarm = RecordingAlarmScheduler()
        val controller = controller(alarm)
        var firing: RestTimerController.Firing? = null
        controller.arm(5_000L, 5) { firing = it }
        alarm.fire()

        assertEquals(true, firing?.isCurrent())
        controller.arm(9_000L, 9)
        assertEquals(false, firing?.isCurrent())

        var second: RestTimerController.Firing? = null
        controller.arm(12_000L, 12) { second = it }
        alarm.fire()
        controller.disarm()
        assertEquals(false, second?.isCurrent())
    }

    @Test
    fun `re-arming the same live deadline is idempotent`() {
        val alarm = RecordingAlarmScheduler()
        val controller = controller(alarm)

        controller.arm(deadlineMillis = 5_000L, totalSeconds = 5)
        controller.arm(deadlineMillis = 5_000L, totalSeconds = 5)

        assertEquals(listOf(5_000L), alarm.deadlines)
    }

    @Test
    fun `a deadline already reached is ignored without buzzing`() {
        val alarm = RecordingAlarmScheduler()
        var buzzes = 0
        val controller = controller(alarm) { buzzes++ }

        controller.arm(deadlineMillis = 500L, totalSeconds = 5)

        assertEquals(emptyList<Long>(), alarm.deadlines)
        assertEquals(0, buzzes)
        assertNull(controller.activeRest)
    }

    @Test
    fun `close cancels the pending alarm`() {
        val alarm = RecordingAlarmScheduler()
        var buzzes = 0
        val controller = controller(alarm) { buzzes++ }
        controller.arm(deadlineMillis = 5_000L, totalSeconds = 5)

        controller.close()
        alarm.fire()

        assertEquals(0, buzzes)
        assertNull(controller.activeRest)
    }

    @Test
    fun `a stale callback delivered after replacement neither buzzes nor disturbs the new arm`() {
        // AlarmManager cancellation cannot retract a callback already queued to
        // the handler — the controller's identity guard is what keeps a dead
        // alarm from buzzing or clearing its replacement.
        val alarm = RecordingAlarmScheduler()
        var buzzes = 0
        val controller = controller(alarm) { buzzes++ }
        controller.arm(deadlineMillis = 5_000L, totalSeconds = 5)
        val stale = alarm.captureCallback()

        controller.arm(deadlineMillis = 9_000L, totalSeconds = 9)
        stale()

        assertEquals(0, buzzes)
        assertEquals(RestTimerController.ActiveRest(9_000L, 9), controller.activeRest)

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

        /** The pending callback as the handler would hold it — surviving cancel. */
        fun captureCallback(): () -> Unit = checkNotNull(callback)
    }
}
