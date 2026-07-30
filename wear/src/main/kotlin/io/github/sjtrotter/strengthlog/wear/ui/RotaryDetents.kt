package io.github.sjtrotter.strengthlog.wear.ui

import kotlin.math.absoluteValue
import kotlin.math.sign

/**
 * The crown, counted in notches (brief §6). A rotary scroll arrives as a stream of
 * pixel deltas — many small ones for a slow turn, a few large ones for a flick —
 * and the dial navigates *discrete* things: one exercise, one round. So the pixels
 * are accumulated here and handed on as whole detents, with the leftover carried
 * into the next event so a slow turn still adds up to a step.
 *
 * Sign follows the platform's: a positive `verticalScrollPixels` (the crown turned
 * the way that scrolls a list downward) moves *forward* — the next exercise, the
 * later round.
 */
data class DetentTurn(val detents: Int, val carryPixels: Float)

object RotaryDetents {

    /**
     * Pixels of crown travel per detent — the layer's one tuning knob. Sized so a
     * deliberate quarter-turn steps a couple of rounds rather than flying past the
     * whole exercise, and so a nudge doesn't move anything at all.
     */
    const val DETENT_PIXELS = 48f

    /**
     * [scrollPixels] added to [carryPixels], split into whole detents plus the
     * remainder to carry. A reversal drops the carry: the leftover of a turn one
     * way must not eat the first part of a turn back, or the crown feels sticky
     * exactly when the lifter is correcting an overshoot.
     */
    fun accumulate(
        carryPixels: Float,
        scrollPixels: Float,
        detentPixels: Float = DETENT_PIXELS,
    ): DetentTurn {
        if (detentPixels <= 0f || !scrollPixels.isFinite()) return DetentTurn(0, carryPixels)
        val reversed = carryPixels.sign != 0f && scrollPixels.sign != 0f &&
            carryPixels.sign != scrollPixels.sign
        val total = (if (reversed) 0f else carryPixels) + scrollPixels
        val detents = (total.absoluteValue / detentPixels).toInt() * total.sign.toInt()
        return DetentTurn(detents = detents, carryPixels = total - detents * detentPixels)
    }
}
