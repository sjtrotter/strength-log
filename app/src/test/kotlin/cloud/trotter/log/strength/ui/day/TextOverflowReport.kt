package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.text.TextLayoutResult

/** Names the measured sizes when a text-fit assertion fails, since CI is the only place these tests run. */
internal fun TextLayoutResult.overflowReport(text: String): String =
    "$text overflows: size=$size constraints=${layoutInput.constraints} " +
        "overflowWidth=$didOverflowWidth overflowHeight=$didOverflowHeight lines=$lineCount " +
        "fontSize=${layoutInput.style.fontSize} lineHeight=${layoutInput.style.lineHeight} density=${layoutInput.density.density}x${layoutInput.density.fontScale}"
