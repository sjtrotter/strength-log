package cloud.trotter.log.strength.wear.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/** The dial's authored tactile vocabulary. Call sites name meaning, never hardware effects. */
object DialHaptics {
    enum class Cue { ROTARY_DETENT, BOUNDARY, START, CONFIRM_TICK, UNDO, REST_COMPLETE }

    /** Pure resolver kept explicit so SDK fallbacks cannot drift between call sites. */
    fun feedbackConstant(
        cue: Cue,
        sdkInt: Int = Build.VERSION.SDK_INT,
        wearScrollConstant: Int? = wearScrollConstant(cue),
    ): Int? = when (cue) {
        Cue.ROTARY_DETENT -> wearScrollConstant ?: if (sdkInt >= 34) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        Cue.BOUNDARY -> wearScrollConstant ?: HapticFeedbackConstants.REJECT
        Cue.START -> HapticFeedbackConstants.KEYBOARD_TAP
        Cue.CONFIRM_TICK, Cue.UNDO -> HapticFeedbackConstants.CONFIRM
        Cue.REST_COMPLETE -> null
    }

    fun perform(view: View, cue: Cue) {
        feedbackConstant(cue)?.let(view::performHapticFeedback)
    }

    /** Wear SDK 34.1's rotary constants are absent on older watches; reflection is the API gate. */
    private fun wearScrollConstant(cue: Cue): Int? {
        val method = when (cue) {
            Cue.ROTARY_DETENT -> "getScrollTick"
            Cue.BOUNDARY -> "getScrollLimit"
            else -> return null
        }
        return runCatching {
            Class.forName("com.google.wear.input.WearHapticFeedbackConstants")
                .getMethod(method)
                .invoke(null) as Int
        }.getOrNull()
    }

    fun restComplete(context: Context) {
        val appContext = context.applicationContext
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(
            VibrationEffect.createOneShot(REST_COMPLETE_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE),
        )
    }

    private const val REST_COMPLETE_MILLIS = 400L
}
