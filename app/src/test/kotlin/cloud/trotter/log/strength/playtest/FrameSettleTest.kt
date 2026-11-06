package cloud.trotter.log.strength.playtest

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSettleTest {
    @Test fun requiresThreeStableFramesBeforeAndAfterPendingWork() {
        var clock = 0L
        var waits = 0
        val frames = settleFrames({ clock }, { waits++ }) {
            clock += 16
            "same tree"
        }
        assertEquals(6, frames)
        assertEquals(96L, clock)
        assertEquals(1, waits)
    }

    @Test fun changedTreeRestartsStabilityCount() {
        var clock = 0L
        val trees = (listOf("old", "old") + List(6) { "new" }).iterator()
        val frames = settleFrames({ clock }, {}) {
            clock += 16
            trees.next()
        }
        assertEquals(8, frames)
        assertEquals(128L, clock)
    }

    @Test fun pendingWorkChangesTheTreeBeforeStabilityIsAccepted() {
        var clock = 0L
        var tree = "old page"
        val seen = mutableListOf<String>()
        val frames = settleFrames({ clock }, { tree = "destination page" }) {
            clock += 16
            tree.also { seen += it }
        }
        assertEquals(6, frames)
        assertEquals(List(3) { "old page" } + List(3) { "destination page" }, seen)
    }

    @Test fun unchangedTreeWaitsForWindowTransitionBeforeCountingStableFrames() {
        var clock = 0L
        var waits = 0
        val frames = settleFrames({ clock }, { waits++ }, isIdle = { clock >= 320L }) {
            clock += 16
            "stationary sheet"
        }
        assertEquals(25, frames)
        assertEquals(400L, clock)
        assertEquals(1, waits)
    }

    @Test fun pendingWorkCanStartAWindowTransitionBeforeTheSecondStablePass() {
        var clock = 0L
        var waiting = false
        val frames = settleFrames({ clock }, { waiting = true }, isIdle = { !waiting || clock >= 320L }) {
            clock += 16
            "stationary sheet"
        }
        assertEquals(22, frames)
        assertEquals(352L, clock)
    }

    @Test fun windowRemovalRestartsStabilityEvenWhenTheTreeIsUnchanged() {
        var clock = 0L
        val windows = (List(4) { setOf("activity", "sheet") } + List(3) { setOf("activity") }).iterator()
        val frames = settleFrames({ clock }, {}) {
            clock += 16
            "unchanged tree" to windows.next()
        }
        assertEquals(7, frames)
        assertEquals(112L, clock)
    }

    @Test fun unfinishedWindowTransitionStopsAtTheFrameCap() {
        var clock = 0L
        val frames = settleFrames({ clock }, { error("A transition never reaches the storage wait") }, isIdle = { false }) {
            clock += 16
            "stationary sheet"
        }
        assertEquals(125, frames)
        assertEquals(2_000L, clock)
    }

    @Test fun changingTreeStopsAtTwoSecondsOfFrameTime() {
        var clock = 0L
        val frames = settleFrames({ clock }, { error("Changing frames never reach the storage wait") }) {
            clock += 16
            clock
        }
        assertEquals(125, frames)
        assertEquals(2_000L, clock)
    }

    @Test fun pendingWorkDoesNotResetTheFrameCap() {
        var clock = 0L
        var waited = false
        val frames = settleFrames({ clock }, { waited = true }) {
            clock += 16
            if (clock >= 1_920L && !waited) 0L else clock
        }
        assertEquals(true, waited)
        assertEquals(125, frames)
        assertEquals(2_000L, clock)
    }
}
