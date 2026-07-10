package io.github.sjtrotter.strengthlog.sync

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import io.github.sjtrotter.strengthlog.domain.sync.SyncCodec
import io.github.sjtrotter.strengthlog.domain.sync.WearSyncPaths
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

/**
 * Receives watch->phone set-edit deltas over the MessageClient (D6, m5-wear.md
 * #20). Google Play Services starts this service (waking the app process if needed)
 * whenever a message lands on [WearSyncPaths.SET_EDIT]; it decodes the delta
 * leniently and applies it through [SetEditApplier], which validates, dedupes and
 * cascades on the phone.
 *
 * Robustness contract: a malformed, foreign or unknown-path payload is logged and
 * dropped — it must never crash the service (an exported component processing bytes
 * from another app has to assume hostile input). Decoding and application are both
 * wrapped so no exception escapes the callback.
 *
 * The work runs synchronously ([runBlocking]) inside the callback: the framework
 * keeps the service alive for the duration of [onMessageReceived], so the single
 * bounded read-modify-write completes before the process can be torn down —
 * launching it into a scope that [onDestroy] cancels would risk losing the write.
 */
@AndroidEntryPoint
class StrengthLogWearListenerService : WearableListenerService() {

    @Inject lateinit var applier: SetEditApplier

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearSyncPaths.SET_EDIT) return
        val delta = try {
            SyncCodec.decodeDelta(event.data)
        } catch (e: Exception) {
            Log.w(TAG, "dropping malformed set-edit payload", e)
            return
        }
        try {
            runBlocking { applier.apply(delta) }
        } catch (e: Exception) {
            Log.w(TAG, "dropping set-edit that failed to apply", e)
        }
    }

    private companion object {
        const val TAG = "WearListener"
    }
}
