package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Asserts [text] shows on one whole line inside its node. Robolectric reports
 * didOverflowWidth for single-line text that fits (e.g. 136 px of 361), so this
 * checks the drawn line against the node bounds instead of hasVisualOverflow.
 */
internal fun TextLayoutResult.assertFitsOneLine(text: String, bounds: Rect) {
    val report = "$text: size=$size constraints=${layoutInput.constraints} bounds=$bounds lines=$lineCount"
    assertEquals(report, 1, lineCount)
    assertFalse(report, isLineEllipsized(0))
    assertFalse(report, didOverflowHeight)
    assertTrue(report, bounds.width + 1f >= getLineRight(0) - getLineLeft(0))
    assertTrue(report, bounds.height + 1f >= getLineBottom(0) - getLineTop(0))
}
