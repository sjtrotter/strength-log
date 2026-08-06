package cloud.trotter.log.strength.wear.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent

/**
 * The wiring half of the crown layer (brief §6): rotary events only reach a
 * composable that is *focused*, so the dial asks for focus once and keeps it —
 * there is nothing else on screen to give it to.
 *
 * The pixels themselves are turned into detents by [RotaryDetents]; all this holds
 * is the carry between two events, which is deliberately a plain box rather than
 * snapshot state — it is read and written inside the event callback and must never
 * invalidate a composition of its own.
 */
@Composable
fun rememberCrownModifier(enabled: Boolean, onDetents: (Int) -> Unit): Modifier {
    val focusRequester = remember { FocusRequester() }
    val carry = remember { CarryPixels() }
    val latestOnDetents by rememberUpdatedState(onDetents)

    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        // A focus request before the node is attached would throw; the dial is the
        // only focus target on the watch, so failing to get it costs the crown and
        // nothing else — never a crash on the wrist.
        runCatching { focusRequester.requestFocus() }
    }

    return Modifier
        .onRotaryScrollEvent { event ->
            if (!enabled) return@onRotaryScrollEvent false
            val turn = RotaryDetents.accumulate(carry.value, event.verticalScrollPixels)
            carry.value = turn.carryPixels
            if (turn.detents != 0) latestOnDetents(turn.detents)
            true
        }
        .focusRequester(focusRequester)
        .focusable(enabled = enabled)
}

private class CarryPixels(var value: Float = 0f)
