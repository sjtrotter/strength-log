package cloud.trotter.log.strength.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** The phone's authored tactile vocabulary. Call sites name meaning, never hardware effects. */
object AppHaptics {
    enum class Cue { CONFIRM_TICK, UNTICK, FINISH, BOUNDARY, STEP_DETENT }

    /** Pure resolver kept explicit so SDK fallbacks cannot drift between call sites. */
    fun feedbackConstant(cue: Cue, sdkInt: Int = Build.VERSION.SDK_INT): Int = when (cue) {
        Cue.CONFIRM_TICK, Cue.FINISH -> HapticFeedbackConstants.CONFIRM
        Cue.UNTICK -> HapticFeedbackConstants.CLOCK_TICK
        Cue.BOUNDARY -> HapticFeedbackConstants.REJECT
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
