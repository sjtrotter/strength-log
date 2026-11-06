package cloud.trotter.log.strength.playtest

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSettleTest {
    @Test fun stopsAfterThreeStableFrames() {
        var clock = 0L
        val frames = settleFrames({ clock }) {
            clock += 16
            "same tree"
        }
        assertEquals(3, frames)
        assertEquals(48L, clock)
    }

    @Test fun changedTreeRestartsStabilityCount() {
        var clock = 0L
        val trees = listOf("old", "old", "new", "new", "new").iterator()
        val frames = settleFrames({ clock }) {
            clock += 16
            trees.next()
        }
        assertEquals(5, frames)
        assertEquals(80L, clock)
    }

    @Test fun changingTreeStopsAtTwoSecondsOfFrameTime() {
        var clock = 0L
        val frames = settleFrames({ clock }) {
            clock += 16
            clock
        }
        assertEquals(125, frames)
        assertEquals(2_000L, clock)
    }
}
