package io.github.sjtrotter.strengthlog.wear.ui

/**
 * Turns raw rotary-crown scroll pixels into whole detents. `onRotaryScrollEvent`
 * reports fractional pixel deltas per frame, not discrete clicks, so this
 * accumulates them and only fires once the accumulated distance crosses
 * [thresholdPx] — the crown's physical detent granularity is device-specific,
 * so this is a deliberate approximation, not a literal hardware readout.
 *
 * One call site owns one instance (per exercise-stream screen); it is not
 * itself a Compose state holder, just a plain accumulator a composable
 * `remember`s.
 */
class RotaryAccumulator(private val thresholdPx: Float = 36f) {
    private var accumulated = 0f

    /** Returns how many whole detents [deltaPx] completed (signed; usually -1/0/+1). */
    fun onScroll(deltaPx: Float): Int {
        accumulated += deltaPx
        var steps = 0
        while (accumulated >= thresholdPx) {
            steps++
            accumulated -= thresholdPx
        }
        while (accumulated <= -thresholdPx) {
            steps--
            accumulated += thresholdPx
        }
        return steps
    }
}
