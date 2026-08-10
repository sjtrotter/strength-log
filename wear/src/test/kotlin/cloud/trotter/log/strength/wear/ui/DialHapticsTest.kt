package cloud.trotter.log.strength.wear.ui

import android.view.HapticFeedbackConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DialHapticsTest {
    @Test fun `rotary detent uses segment texture on api 34`() {
        assertEquals(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, DialHaptics.feedbackConstant(DialHaptics.Cue.ROTARY_DETENT, 34, null))
    }

    @Test fun `rotary detent falls back before api 34`() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, DialHaptics.feedbackConstant(DialHaptics.Cue.ROTARY_DETENT, 33, null))
    }

    @Test fun `wear rotary constant wins when the platform exposes it`() {
        assertEquals(913, DialHaptics.feedbackConstant(DialHaptics.Cue.ROTARY_DETENT, 30, 913))
        assertEquals(914, DialHaptics.feedbackConstant(DialHaptics.Cue.BOUNDARY, 30, 914))
    }

    @Test fun `rest completion is the vocabulary's vibration effect`() {
        assertNull(DialHaptics.feedbackConstant(DialHaptics.Cue.REST_COMPLETE, 37, null))
    }
}
