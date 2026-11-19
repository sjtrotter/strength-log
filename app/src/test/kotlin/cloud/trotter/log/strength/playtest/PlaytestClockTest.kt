package cloud.trotter.log.strength.playtest

import android.os.Looper
import android.os.Handler
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
class PlaytestClockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settlingNeverMovesTheElapsedClock() {
        val time = PlaytestTime()
        val before = time.cardioClock.elapsedRealtimeMillis()
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { Text("Stable screen") } }
        var frames = 0
        val deadline = StepDeadline(10_000)
        deadline.run(compose) {
            compose.settleSemantics(deadline, awaitPendingWork = {}) { frames++ }
        }
        assertTrue(frames >= 6)
        assertEquals(before, time.elapsedMillis())
        assertEquals(before, time.cardioClock.elapsedRealtimeMillis())
    }

    @Test fun advanceFiresEveryBoundaryInOrder() {
        val time = PlaytestTime()
        val alarm = PlaytestCardioAlarm()
        val start = time.elapsedMillis()
        val fired = mutableListOf<Long>()
        val handler = Handler(Looper.getMainLooper())
        fun arm() {
            val delay = if (fired.size % 2 == 0) 60_000L else 90_000L
            alarm.arm(time.elapsedMillis() + delay, "boundary-${fired.size}") {
                fired += time.elapsedMillis() - start
                if (fired.size < 20) handler.post { arm() }
            }
        }
        arm()
        advanceClock(time, alarm, Duration.ofMinutes(25), idle = { span ->
            shadowOf(Looper.getMainLooper()).idleFor(span)
        }, fire = { callback -> compose.runOnUiThread { callback() } })
        val expected = (0 until 20).map { index -> (index / 2) * 150_000L + if (index % 2 == 0) 60_000L else 150_000L }
        assertEquals(expected, fired)
        assertEquals(start + 1_500_000L, time.elapsedMillis())
        assertEquals(null, alarm.pendingDeadline)
    }

    @Test fun cardioClockAndWallClockMoveTogether() {
        val time = PlaytestTime()
        val beforeWall = time.clock.instant()
        val beforeElapsed = time.elapsedMillis()
        time.set(beforeWall.plusSeconds(7), time.clock.zone)
        assertEquals(beforeElapsed, time.elapsedMillis())
        time.advance(Duration.ofSeconds(90))
        assertEquals(beforeWall.plusSeconds(97), time.clock.instant())
        assertEquals(beforeElapsed + 90_000L, time.elapsedMillis())
        assertEquals(time.elapsedMillis(), time.cardioClock.elapsedRealtimeMillis())
        assertEquals(time.clock.millis(), time.cardioClock.wallMillis())
    }
}
