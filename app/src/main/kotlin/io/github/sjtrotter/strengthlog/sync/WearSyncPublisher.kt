package io.github.sjtrotter.strengthlog.sync

import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.domain.sync.SyncCodec
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.sync.WearSyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.tasks.await

/**
 * Watches the phone's live state and mirrors the *suggested* day onto the Wearable
 * Data Layer as one always-current [WatchSnapshot] item (D6, m5-wear.md #20). Runs
 * for the whole app-process lifetime — [start] is called once from
 * [io.github.sjtrotter.strengthlog.StrengthLogApp]; no foreground service, no
 * lifecycle of its own.
 *
 * Conflation: the state derives from six flows; identical content is published at
 * most once ([distinctUntilChanged] on the pre-revision snapshot), and only a real
 * content change spends a new [revision][WearSyncStore.nextRevision]. Because the
 * counter is persisted and this collector is fresh on every process start, the
 * first emission after a restart always publishes with a bumped revision — which is
 * the byte change that wakes a watch waiting to re-drain queued edits (§11.4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearSyncPublisher(
    private val repo: TrackerRepository,
    private val store: WearSyncStore,
    private val dataClient: DataClient,
    parentScope: CoroutineScope,
) {

    private val scope = parentScope + SupervisorJob()
    private var started = false

    fun start() {
        if (started) return
        started = true
        snapshotContent()
            .distinctUntilChanged()
            .onEach { content -> content?.let { publish(it) } }
            .launchIn(scope)
    }

    /** The suggested day projected to a snapshot with a placeholder revision (0);
     *  the real revision is stamped at publish so dedupe compares only content. */
    private fun snapshotContent() =
        repo.suggestedDayFlow.flatMapLatest { dayId ->
            if (dayId == null) {
                flowOf(null)
            } else {
                val program = combine(
                    repo.programFlow,
                    repo.daySlotsFlow(dayId),
                    repo.logFlow(dayId),
                ) { program, slots, logs -> Triple(program, slots, logs) }
                val context = combine(
                    repo.configFlow,
                    repo.catalogFlow,
                    repo.unitFlow,
                ) { cfg, catalog, unit -> Triple(cfg, catalog, unit) }
                combine(program, context) { (prog, slots, logs), (cfg, catalog, unit) ->
                    WatchSnapshotBuilder.build(
                        program = prog,
                        suggestedDayId = dayId,
                        slots = slots,
                        logs = logs,
                        cfg = cfg,
                        catalog = catalog,
                        unit = unit,
                        revision = 0L,
                    )
                }
            }
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
