package cloud.trotter.log.strength.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** The phone's authored tactile vocabulary. Call sites name meaning, never hardware effects. */
object AppHaptics {
    enum class Cue { CONFIRM_TICK, UNTICK, FINISH, BOUNDARY, STEP_DETENT }

    /** Pure resolver kept explicit so SDK fallbacks cannot drift between call sites. */
    fun feedbackConstant(cue: Cue, sdkInt: Int = Build.VERSION.SDK_INT): Int = when (cue) {
        // CONFIRM/REJECT arrived in API 30; minSdk is 26, where the closest
        // honest stand-ins are the framework's press and long-press effects.
        Cue.CONFIRM_TICK, Cue.FINISH -> if (sdkInt >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        Cue.UNTICK -> HapticFeedbackConstants.CLOCK_TICK
        Cue.BOUNDARY -> if (sdkInt >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        Cue.STEP_DETENT -> if (sdkInt >= 34) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
    }

    fun perform(view: View, cue: Cue) {
        view.performHapticFeedback(feedbackConstant(cue))
    }
}
