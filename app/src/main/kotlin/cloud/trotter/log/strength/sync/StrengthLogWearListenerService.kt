package cloud.trotter.log.strength.sync

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WearSyncPaths
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
    @Inject lateinit var cardioApplier: CardioDeltaApplier
    @Inject lateinit var syncStore: WearSyncStore

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearSyncPaths.SET_EDIT -> receiveSetEdit(event.data)
            // Swaps ride their own path (#90) so an older phone drops them by filter
            // rather than failing to decode them as a set edit.
            WearSyncPaths.EXERCISE_SWAP ->
                receive("exercise-swap", { SyncCodec.decodeSwap(event.data) }) { applier.apply(it) }
            WearSyncPaths.CARDIO ->
                receive("cardio", { SyncCodec.decodeCardio(event.data) }) { cardioApplier.apply(it) }
        }
    }

    private fun receiveSetEdit(bytes: ByteArray) {
        val delta = try { SyncCodec.decodeDelta(bytes) } catch (e: Exception) {
            Log.w(TAG, "dropping malformed set-edit payload", e)
            return
        }
        try {
            runBlocking {
                syncStore.beginIncomingChange()
                val outcome = applier.apply(delta)
                val tick = if (outcome == SetEditApplier.Outcome.APPLIED && delta.done == true) {
                    RemoteTick(delta.dayId, delta.programExerciseId, delta.setIndex)
                } else null
                syncStore.finishIncomingChange(tick)
            }
        } catch (e: Exception) {
            Log.w(TAG, "dropping set-edit that failed to apply", e)
            runCatching { runBlocking { syncStore.finishIncomingChange(null) } }
        }
    }

    /** Decode-then-apply with the robustness contract above: neither half may throw
     *  out of the callback, and a payload that fails either is logged and dropped. */
    private fun <T> receive(
        kind: String,
        decode: () -> T,
        apply: suspend (T) -> Any,
    ) {
        val message = try {
            decode()
        } catch (e: Exception) {
            Log.w(TAG, "dropping malformed $kind payload", e)
            return
        }
        try {
            runBlocking { apply(message) }
        } catch (e: Exception) {
            Log.w(TAG, "dropping $kind that failed to apply", e)
        }
    }

    private companion object {
        const val TAG = "WearListener"
    }
}
