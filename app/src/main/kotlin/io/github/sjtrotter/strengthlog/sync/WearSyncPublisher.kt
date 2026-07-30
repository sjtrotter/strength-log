package io.github.sjtrotter.strengthlog.sync

import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import io.github.sjtrotter.strengthlog.domain.sync.SyncCodec
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.sync.WearSyncPaths
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
 * once from [io.github.sjtrotter.strengthlog.StrengthLogApp]; no foreground
 * service, no lifecycle of its own.
 *
 * Conflation: identical content is published at most once ([distinctUntilChanged]
 * on the pre-revision snapshot), and only a real content change spends a new
 * [revision][WearSyncStore.nextRevision]. Because the counter is persisted and this
 * collector is fresh on every process start, the first emission after a restart
 * always publishes with a bumped revision — which is the byte change that wakes a
 * watch waiting to re-drain queued edits (§11.4).
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
        val snapshot = content.copy(revision = store.nextRevision())
        val request = PutDataRequest.create(WearSyncPaths.SNAPSHOT).apply {
            data = SyncCodec.encodeSnapshot(snapshot)
            setUrgent()
        }
        try {
            dataClient.putDataItem(request).await()
        } catch (e: Exception) {
            // A failed publish is not fatal: the next state change republishes, and
            // the watch keeps rendering its cached snapshot until then.
            Log.w(TAG, "snapshot publish failed", e)
        }
    }

    private companion object {
        const val TAG = "WearSyncPublisher"
    }
}
