package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WearSyncPublisherSizeTest {

    private fun snapshot(setCount: Int) = WatchSnapshot(
        revision = 0L,
        suggestedDayId = "A",
        day = WatchDay(
            dayId = "A",
            title = "Day A",
            accentIndex = 0,
            exercises = listOf(
                WatchExercise(
                    programExerciseId = 1L,
                    slot = "main",
                    name = "Squat",
                    goal = 235.0,
                    perHand = false,
                    supersetPartnerName = null,
                    sets = List(setCount) { WatchSet(235.0, 5, "WORK", done = false) },
                    ssSets = emptyList(),
                ),
            ),
        ),
        unit = "lb",
    )

    @Test
    fun `oversize snapshot spends no revision and publishes nothing`() = runTest {
        var revisions = 0
        var publishes = 0
        var warnedSize = 0

        val published = publishSnapshotWithinSizeLimit(
            content = snapshot(setCount = 2_000),
            spendStamp = { revisions++; SnapshotStamp(epoch = 1L, revision = 1L) },
            publishBytes = { publishes++ },
            warnOversize = { warnedSize = it },
        )

        assertFalse(published)
        assertTrue(warnedSize > WearSyncPublisher.MAX_SNAPSHOT_BYTES)
        assertEquals(0, revisions)
        assertEquals(0, publishes)
    }

    @Test
    fun `normal day records ample margin below payload bound`() = runTest {
        val normal = snapshot(setCount = 6)
        val typicalSize = SyncCodec.encodeSnapshot(normal.copy(revision = Long.MAX_VALUE)).size
        println("typical snapshot bytes=$typicalSize; bound=${WearSyncPublisher.MAX_SNAPSHOT_BYTES}")

        assertTrue(typicalSize < WearSyncPublisher.MAX_SNAPSHOT_BYTES / 10)
        assertTrue(
            publishSnapshotWithinSizeLimit(normal, spendStamp = { SnapshotStamp(epoch = 1L, revision = 1L) }, publishBytes = {}),
        )
    }
}
