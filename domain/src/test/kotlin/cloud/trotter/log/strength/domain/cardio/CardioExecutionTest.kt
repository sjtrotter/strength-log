package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardioExecutionTest {
    private val start = Clocks(1_000_000L, 9_000_000L, 7)

    private fun Clocks.advance(millis: Long): Clocks =
        copy(wallMillis = wallMillis + millis, elapsedMillis = elapsedMillis + millis)

    private fun time(role: CardioRole, seconds: Int, aim: Int? = null, hard: Boolean = false) =
        CardioSetSpec(role, Measure.Time(seconds), aim?.let { Measure.Distance(it) }, hard)

    private fun distance(role: CardioRole = CardioRole.WORK) =
        CardioSetSpec(role, Measure.Distance(400), Measure.Time(105), hard = role == CardioRole.WORK)

    private fun execution(vararg sets: CardioSetSpec): CardioExecution =
        CardioExecution(CardioBlock(CardioActivity.TRACK_RUN, sets.toList()))

    @Test
    fun `same boot uses elapsed time even when wall time jumps`() {
        val run = execution(distance()).start(start)
        assertEquals(40_000L, run.elapsedMillis(Clocks(4_640_000L, 9_040_000L, 7)))
        assertEquals(40_000L, run.elapsedMillis(Clocks(100L, 9_040_000L, 7)))
        assertEquals(0L, run.elapsedMillis(Clocks(4_640_000L, 8_999_999L, 7)))
    }

    @Test
    fun `changed boot uses wall time even when elapsed time is larger`() {
        val run = execution(distance()).start(start)
        assertEquals(45_000L, run.elapsedMillis(Clocks(1_045_000L, 309_000_000L, 8)))
        assertEquals(45_000L, run.elapsedMillis(Clocks(1_045_000L, 500L, 8)))
        assertEquals(0L, run.elapsedMillis(Clocks(999_999L, 500L, 8)))
    }

    @Test
    fun `unknown boot or missing elapsed anchor falls back to wall time`() {
        val unknownBoot = BootAnchor(1_000_000L, 9_000_000L, null)
        assertEquals(45_000L, unknownBoot.elapsedSince(Clocks(1_045_000L, 500L, 8)))
        assertEquals(45_000L, unknownBoot.elapsedSince(Clocks(1_045_000L, 500L, null)))
        assertEquals(45_000L, BootAnchor(1_000_000L, 9_000_000L, 7)
            .elapsedSince(Clocks(1_045_000L, 500L, null)))
        assertEquals(45_000L, BootAnchor(1_000_000L, null, 7)
            .elapsedSince(Clocks(1_045_000L, 500L, 7)))
    }

    @Test
    fun `several missed boundaries retain deadlines and elapsed in cooldown`() {
        val initial = execution(time(CardioRole.WORK, 10), time(CardioRole.RECOVERY, 20),
            time(CardioRole.COOLDOWN, 30)).start(start)
        val now = start.advance(35_000L)
        val run = initial.reconcile(now)

        assertEquals(2, run.position)
        assertTrue(run.running)
        assertEquals(listOf(10, 20), run.results.map { it.elapsedSeconds })
        assertEquals(BootAnchor(1_030_000L, 9_030_000L, 7), run.anchor)
        assertEquals(5_000L, run.elapsedMillis(now))
        assertEquals(start.wallMillis, run.startedAt)
        assertNull(run.completedAt)
        assertEquals(0, initial.position)

        val completed = run.reconcile(start.advance(90_000L))
        assertTrue(completed.complete)
        assertFalse(completed.running)
        assertEquals(1_060_000L, completed.completedAt)
        assertEquals(listOf(10, 20, 30), completed.results.map { it.elapsedSeconds })
        assertEquals(completed, completed.reconcile(start.advance(120_000L)))
    }

    @Test
    fun `reboot reconciliation carries wall deadlines through automatic sets`() {
        val now = Clocks(1_035_000L, 309_000_000L, 8)
        val run = execution(time(CardioRole.WORK, 10), time(CardioRole.RECOVERY, 20),
            time(CardioRole.COOLDOWN, 30)).start(start).reconcile(now)

        assertEquals(2, run.position)
        assertEquals(BootAnchor(1_030_000L, 9_030_000L, 7), run.anchor)
        assertEquals(5_000L, run.elapsedMillis(now))
        val completed = run.reconcile(now.copy(wallMillis = 1_090_000L))
        assertEquals(1_060_000L, completed.completedAt)
        assertEquals(60, completed.summary().seconds)
    }

    @Test
    fun `missed boundaries stop at unstarted work and waiting is not elapsed work`() {
        val run = execution(time(CardioRole.WARMUP, 10), time(CardioRole.RECOVERY, 10),
            time(CardioRole.WORK, 10)).start(start).reconcile(start.advance(45_000L))

        assertEquals(2, run.position)
        assertFalse(run.running)
        assertEquals(0L, run.elapsedMillis(start.advance(60_000L)))
        assertEquals(listOf(10, 10), run.results.map { it.elapsedSeconds })
        val resumed = run.start(start.advance(60_000L))
        assertEquals(start.wallMillis, resumed.startedAt)
        assertEquals(3, resumed.elapsedSeconds(start.advance(63_000L)))
    }

    @Test
    fun `warmup does not start automatically after a finished set`() {
        val run = execution(time(CardioRole.WORK, 10), time(CardioRole.WARMUP, 10))
            .start(start).reconcile(start.advance(45_000L))
        assertEquals(1, run.position)
        assertFalse(run.running)
    }

    @Test
    fun `distance end waits for reached tap beyond its time aim`() {
        val now = start.advance(127_000L)
        val run = execution(distance()).start(start).reconcile(now)
        assertEquals(0, run.position)
        assertTrue(run.running)
        assertEquals(127, run.elapsedSeconds(now))
        assertNull(run.completedAt)

        val completed = run.reachTarget(now)
        assertTrue(completed.complete)
        assertFalse(completed.running)
        assertEquals(1_127_000L, completed.completedAt)
        assertEquals(127, completed.results.single().elapsedSeconds)
        assertEquals(400, completed.results.single().metres)
        assertEquals(DistanceSource.MANUAL, completed.results.single().distanceSource)
        assertTrue(completed.distanceEntries.isEmpty())
    }

    @Test
    fun `reached distance starts recovery or cooldown but leaves work ready`() {
        listOf(CardioRole.RECOVERY, CardioRole.COOLDOWN, CardioRole.WORK).forEach { role ->
            val now = start.advance(105_000L)
            val run = execution(distance(), time(role, 60)).start(start).reachTarget(now)
            assertEquals(1, run.position)
            assertEquals(role != CardioRole.WORK, run.running)
            if (run.running) assertEquals(BootAnchor(1_105_000L, 9_105_000L, 7), run.anchor)
        }
    }

    @Test
    fun `automatic distance recovery starts but never finishes by inference`() {
        val now = start.advance(200_000L)
        val run = execution(time(CardioRole.WORK, 10), distance(CardioRole.RECOVERY),
            time(CardioRole.COOLDOWN, 30)).start(start).reconcile(now)
        assertEquals(1, run.position)
        assertTrue(run.running)
        assertEquals(190, run.elapsedSeconds(now))
    }

    @Test
    fun `timed distance aims request entry while recovery continues`() {
        val now = start.advance(15_000L)
        val run = execution(time(CardioRole.WORK, 10, aim = 400), time(CardioRole.RECOVERY, 20))
            .start(start).reconcile(now)
        assertEquals(listOf(0), run.distanceEntries)
        assertTrue(run.running)
        assertEquals(5, run.elapsedSeconds(now))
        assertNull(run.results.single().metres)

        val entered = run.enterDistance(0, 390)
        assertTrue(entered.distanceEntries.isEmpty())
        assertEquals(390, entered.results.single().metres)
        assertEquals(DistanceSource.MANUAL, entered.results.single().distanceSource)
        assertEquals(run.anchor, entered.anchor)
        assertEquals(listOf(0), run.distanceEntries)
        assertNull(run.results.single().metres)

        val blank = run.enterDistance(0, null)
        assertTrue(blank.distanceEntries.isEmpty())
        assertNull(blank.results.single().metres)
        assertNull(blank.results.single().distanceSource)
    }

    @Test
    fun `pending distance entries survive several boundaries and completion`() {
        val run = execution(time(CardioRole.WORK, 10, aim = 400), time(CardioRole.RECOVERY, 20, aim = 100),
            time(CardioRole.COOLDOWN, 30, aim = 200)).start(start).reconcile(start.advance(65_000L))
        assertTrue(run.complete)
        assertEquals(1_060_000L, run.completedAt)
        assertEquals(listOf(0, 1, 2), run.distanceEntries)

        val amended = run.enterDistance(1, 95)
        assertEquals(listOf(0, 2), amended.distanceEntries)
        assertEquals(listOf(null, 95, null), amended.results.map { it.metres })
        assertEquals(run.summary(), amended.summary())
        assertEquals(run.completedAt, amended.completedAt)
    }

    @Test
    fun `stopping requests distance and leaves recovery ready`() {
        val now = start.advance(4_000L)
        val run = execution(distance(), time(CardioRole.RECOVERY, 20)).start(start)
            .finishStep(CardioSetStatus.SHORTENED, 4, now)
        assertEquals(listOf(0), run.distanceEntries)
        assertFalse(run.running)
        assertEquals(CardioSetStatus.SHORTENED, run.results.single().status)
        assertEquals(4, run.results.single().elapsedSeconds)
    }

    @Test
    fun `skipping and ending never infer recovery starts or distance entries`() {
        var run = execution(time(CardioRole.WARMUP, 10), distance(), time(CardioRole.RECOVERY, 20))
            .finishStep(CardioSetStatus.SKIPPED, 0, start)
        assertFalse(run.running)
        run = run.start(start).finishStep(CardioSetStatus.SHORTENED, 4, start.advance(4_000L),
            requestDistance = false)
        assertFalse(run.running)
        run = run.finishStep(CardioSetStatus.SKIPPED, 0, start.advance(4_000L))
        assertTrue(run.complete)
        assertTrue(run.distanceEntries.isEmpty())
        assertEquals(1_004_000L, run.completedAt)
        assertEquals(CardioSummary(CardioMode.OUTDOOR_RUN, true, "Track run", 4, 0), run.summary())
    }

    @Test
    fun `six 400 meter intervals summarize actual time and finished sets`() {
        val block = CardioBlock(CardioActivity.TRACK_RUN, buildList {
            add(time(CardioRole.WARMUP, 300))
            repeat(6) {
                add(distance())
                add(time(CardioRole.RECOVERY, 60))
            }
            add(time(CardioRole.COOLDOWN, 300))
        })
        var now = start.advance(300_000L)
        var run = CardioExecution(block).start(start).reconcile(now)
        repeat(6) {
            now = now.advance(20_000L)
            run = run.start(now)
            now = now.advance(105_000L)
            run = run.reachTarget(now)
            now = now.advance(60_000L)
            run = run.reconcile(now)
        }
        now = now.advance(300_000L)
        run = run.reconcile(now)

        assertTrue(run.complete)
        assertEquals(CardioSummary(CardioMode.OUTDOOR_RUN, true, "Track run", 1_590, 14), run.summary())
        assertEquals(start.wallMillis, run.startedAt)
        assertEquals(2_710_000L, run.completedAt)
        assertEquals(List(6) { 400 }, run.results.filter { it.spec.end is Measure.Distance }.map { it.metres })
        assertTrue(run.distanceEntries.isEmpty())
    }

    @Test
    fun `thirty minute road run summarizes planned seconds without overrun`() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(time(CardioRole.WORK, 1_800)))
        val run = CardioExecution(block).start(start).reconcile(start.advance(1_840_000L))
        assertTrue(run.complete)
        assertEquals(2_800_000L, run.completedAt)
        assertEquals(CardioSummary(CardioMode.OUTDOOR_RUN, false, "Road run", 1_800, 1), run.summary())
    }

    @Test
    fun `summary counts only finished steps and hard work across the frozen block`() {
        var run = execution(time(CardioRole.WARMUP, 10, hard = true), time(CardioRole.WORK, 20),
            time(CardioRole.WORK, 30, hard = true)).start(start).reconcile(start.advance(10_000L))
        assertTrue(run.summary().hard)
        run = run.start(start.advance(10_000L))
            .finishStep(CardioSetStatus.SHORTENED, 4, start.advance(14_000L))
            .finishStep(CardioSetStatus.SKIPPED, 0, start.advance(14_000L))
        assertEquals(CardioSummary(CardioMode.OUTDOOR_RUN, true, "Track run", 14, 1), run.summary())

        val easyBlock = CardioBlock(CardioActivity.TREADMILL_RUN,
            listOf(time(CardioRole.COOLDOWN, 10, hard = true)))
        val easy = CardioExecution(easyBlock).start(start).reconcile(start.advance(10_000L))
        assertEquals(CardioSummary(CardioMode.TREADMILL, false, "Treadmill run", 10, 1), easy.summary())
        assertEquals(CardioMode.LOW_IMPACT, easy.copy(block = easyBlock.copy(activity = CardioActivity.ROWER)).summary().mode)
    }
}
