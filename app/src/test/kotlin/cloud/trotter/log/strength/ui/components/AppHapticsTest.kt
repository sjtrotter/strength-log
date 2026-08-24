package cloud.trotter.log.strength.ui.components

import android.view.HapticFeedbackConstants
import kotlin.test.Test
import kotlin.test.assertEquals

class AppHapticsTest {
    @Test fun `step detent uses segment texture on api 34`() {
        assertEquals(
            HapticFeedbackConstants.SEGMENT_TICK,
            AppHaptics.feedbackConstant(AppHaptics.Cue.STEP_DETENT, 34),
        )
    }

    @Test fun `step detent falls back before api 34`() {
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            AppHaptics.feedbackConstant(AppHaptics.Cue.STEP_DETENT, 33),
        )
    }

    @Test fun `semantic cues resolve to their authored constants`() {
        assertEquals(HapticFeedbackConstants.CONFIRM, AppHaptics.feedbackConstant(AppHaptics.Cue.CONFIRM_TICK, 33))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, AppHaptics.feedbackConstant(AppHaptics.Cue.UNTICK, 33))
        assertEquals(HapticFeedbackConstants.CONFIRM, AppHaptics.feedbackConstant(AppHaptics.Cue.FINISH, 33))
        assertEquals(HapticFeedbackConstants.REJECT, AppHaptics.feedbackConstant(AppHaptics.Cue.BOUNDARY, 33))
    }
}
