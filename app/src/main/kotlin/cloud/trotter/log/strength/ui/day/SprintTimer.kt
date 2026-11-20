package cloud.trotter.log.strength.ui.day

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import javax.inject.Inject
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/** Practice (design call 1): time to set the phone hand at the start line and settle. */
internal const val SPRINT_BEEP_DELAY_MILLIS = 5_000L

/** Preview and screen tests freeze the display clock without changing the scheduled beep. */
internal val LocalSprintElapsedMillis = staticCompositionLocalOf<() -> Long> { SystemClock::elapsedRealtime }

/** Separate saved state preserves CardioRun's released parcel shape. */
@Parcelize
data class SprintTimerState(val runId: String, val position: Int, val beepElapsedMillis: Long, val bootCount: Int) : Parcelable

@Serializable
@Parcelize
data class CardioSprintTime(val runId: String, val position: Int, val centis: Int, val method: String, val protocol: String) : Parcelable

@Parcelize
data class SprintTimeDraft(val runId: String, val position: Int, val text: String, val method: String, val protocol: String) : Parcelable

interface SprintBeeper { fun beep() }

class AndroidSprintBeeper @Inject constructor() : SprintBeeper {
    override fun beep() {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 200L)
        } catch (failure: RuntimeException) {
            tone.release()
            throw failure
        }
    }
}

/** Hundredths from the beep to [stopElapsedMillis], at least 1; null before the beep. */
internal fun sprintCentis(state: SprintTimerState, stopElapsedMillis: Long): Int? =
    ((stopElapsedMillis - state.beepElapsedMillis) / 10).toInt().takeIf { it >= 1 }
