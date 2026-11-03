package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.saveable.SaverScope
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.sync.WatchCardioBlock
import cloud.trotter.log.strength.domain.sync.WatchCardioSetResult
import cloud.trotter.log.strength.domain.sync.WatchCardioSetSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CardioSetExecutionCompatibilityTest {
    private val work = WatchCardioSetSpec("WORK", "TIME", 10, "DISTANCE", 100, hard = true)
    private val recovery = WatchCardioSetSpec("RECOVERY", "TIME", 20)
    private val cooldown = WatchCardioSetSpec("COOLDOWN", "TIME", 30)
    private val block = WatchCardioBlock("TRACK_RUN", listOf(work, recovery, cooldown))
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    private val releasedSavedState = listOf(
        "plan", 9L, "day", "A",
        "block", """{"activity":"TRACK_RUN","sets":[{"role":"WORK","endKind":"TIME","endValue":10,"aimKind":"DISTANCE","aimValue":100,"hard":true},{"role":"RECOVERY","endKind":"TIME","endValue":20},{"role":"COOLDOWN","endKind":"TIME","endValue":30}]}""",
        "results", """[{"spec":{"role":"WORK","endKind":"TIME","endValue":10,"aimKind":"DISTANCE","aimValue":100,"hard":true},"status":"FINISHED","elapsedSeconds":10}]""",
        "start", 1_000_000L, "wall", 1_010_000L, "elapsed", 9_010_000L, "boot", 7,
        "end", null, "entries", arrayListOf(0), "draft", 95,
        "options", false, "confirm", false, "selection", 2,
        "queued", null, "finishesDay", true, "saveFailed", false,
    )

    @Test
    fun `released saved shape restores running recovery and pending crown distance unchanged`() {
        val restored = assertNotNull(cardioSetStateSaver.restore(releasedSavedState))
        assertEquals(CardioSetState(9L, "A", block,
            results = listOf(WatchCardioSetResult(work, "FINISHED", 10)),
            startedAt = 1_000_000L, active = CardioAnchors(1_010_000L, 9_010_000L, 7, 9L),
            entries = listOf(0), draftMetres = 95, selection = 2, finishesDay = true), restored)
        assertEquals(releasedSavedState, with(cardioSetStateSaver) { scope.save(restored) })
        assertEquals(5, restored.elapsed(9_015_000L, 100L, 7))
        assertEquals(9_030_000L, restored.deadline(9_015_000L, 100L, 7))
    }

    @Test
    fun `reboot adopts current elapsed epoch only at a boundary and keeps delta identity`() {
        val restored = assertNotNull(cardioSetStateSaver.restore(releasedSavedState))
        val beforeBoundary = restored.resume(1_025_000L, 308_990_000L, 8)
        assertEquals(restored, beforeBoundary)
        assertEquals(15, beforeBoundary.elapsed(308_990_000L, 1_025_000L, 8))

        val resumed = beforeBoundary.resume(1_035_000L, 309_000_000L, 8)
        assertEquals(CardioAnchors(1_030_000L, 308_995_000L, 8, 9L), resumed.active)
        assertEquals(5, resumed.elapsed(309_000_000L, 1_035_000L, 8))
        val saved = with(cardioSetStateSaver) { assertNotNull(scope.save(resumed)) }
        val running = assertNotNull(cardioSetStateSaver.restore(saved)).tap(1_035_000L, 309_000_000L, 8)
        assertEquals(resumed.active, running.active)
        assertEquals(6, running.elapsed(309_001_000L, 100L, 8))
        assertEquals(running, running.resume(100L, 309_001_000L, 8))

        val complete = running.resume(100L, 309_025_000L, 8)
        assertEquals(CardioDelta(dayId = "A", mode = "OUTDOOR_RUN", hard = true, label = "Track run",
            startedAt = 1_000_000L, completedAt = 1_060_000L, seconds = 60, stepsCompleted = 3,
            stamp = 1_060_000L, planId = 9L, activity = "TRACK_RUN", finishesDay = true,
            sets = listOf(WatchCardioSetResult(work, "FINISHED", 10, 95, "MANUAL"),
                WatchCardioSetResult(recovery, "FINISHED", 20),
                WatchCardioSetResult(cooldown, "FINISHED", 30))), complete.delta())
    }
}
