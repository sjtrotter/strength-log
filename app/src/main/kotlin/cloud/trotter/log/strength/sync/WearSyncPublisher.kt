package cloud.trotter.log.strength.sync

import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.sync.WearSyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.tasks.await

/**
 * Mirrors [TodaySnapshotSource] — the shared projection of the *suggested* day —
 * onto the Wearable Data Layer as one always-current [WatchSnapshot] item (D6,
 * m5-wear.md #20). Runs for the whole app-process lifetime — [start] is called
 * once from [cloud.trotter.log.strength.StrengthLogApp]; no foreground
 * service, no lifecycle of its own.
 *
 * Conflation: identical content is published at most once ([distinctUntilChanged]
 * on the unstamped snapshot), and only a real content change spends a new
 * [stamp][WearSyncStore.nextStamp]. Because the counter is persisted and this
 * collector is fresh on every process start, the first emission after a restart
 * always publishes with a bumped revision — which is the byte change that wakes a
 * watch waiting to re-drain queued edits (§11.4).
 *
 * Every publish carries the epoch as well as the revision, and the two always come
 * from the same [WearSyncStore.nextStamp] call — the watch reads them as one fact.
 */
class WearSyncPublisher(
    private val source: TodaySnapshotSource,
    private val store: WearSyncStore,
    private val dataClient: DataClient,
    parentScope: CoroutineScope,
) {

    private val scope = parentScope + SupervisorJob()
    private var started = false

    fun start() {
        if (started) return
        started = true
        source.snapshots
            .distinctUntilChanged()
            .onEach { content -> content?.let { publish(it) } }
            .launchIn(scope)
    }

    private suspend fun publish(content: WatchSnapshot) {
publishSnapshotWithinSizeLimit(
            content = content,
            spendStamp = store::nextStamp,
            publishBytes = { bytes ->
                val request = PutDataRequest.create(WearSyncPaths.SNAPSHOT).apply {
                    data = bytes
                    setUrgent()
                }
                try {
                    dataClient.putDataItem(request).await()
                } catch (e: Exception) {
                    // A failed publish is not fatal: the next state change republishes, and
                    // the watch keeps rendering its cached snapshot until then.
                    Log.w(TAG, "snapshot publish failed", e)
                }
            },
            warnOversize = { size -> Log.w(TAG, "snapshot is $size bytes; skipping publish (limit $MAX_SNAPSHOT_BYTES)") },
        )
    }

    companion object {
        private const val TAG = "WearSyncPublisher"
        internal const val MAX_SNAPSHOT_BYTES = 90_000
    }
}

/** Size gate separated from Play Services so its no-revision/no-publish contract is JVM-testable. */
internal suspend fun publishSnapshotWithinSizeLimit(
    content: WatchSnapshot,
    spendStamp: suspend () -> SnapshotStamp,
    publishBytes: suspend (ByteArray) -> Unit,
    warnOversize: (Int) -> Unit = {},
): Boolean {
    // Measure with the widest possible stamp before spending the durable one.
    val guardedBytes = SyncCodec.encodeSnapshot(content.copy(revision = Long.MAX_VALUE, epoch = Long.MAX_VALUE))
    if (guardedBytes.size > WearSyncPublisher.MAX_SNAPSHOT_BYTES) {
        // Stale-but-working beats a failed publish that also spends a revision.
        warnOversize(guardedBytes.size)
        return false
    }
    val stamp = spendStamp()
    val bytes = SyncCodec.encodeSnapshot(content.copy(revision = stamp.revision, epoch = stamp.epoch))
    publishBytes(bytes)
    return true
}
