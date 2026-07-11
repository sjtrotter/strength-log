package io.github.sjtrotter.strengthlog.domain.units

/**
 * Stepper increment and display formatting for a TIMED track's hold/carry
 * duration (tracking-types §3). Mirrors [WeightStepper]'s split between
 * unit-aware increment/round and display formatting, but seconds need neither
 * a unit nor a rounding rule — every TIMED row is seeded and edited in whole
 * 5-second steps already, so this is just the two things the UI needs: how
 * big a tap is, and how the value reads.
 */
object SecondsStepper {

    /** Seconds stepper increment (design §3): a fixed ±5s, no threshold. */
    private const val STEP = 5

    /** Below this, seconds read as a bare count ("45s"); at or above, as m:ss
     *  ("1:30") — the same shape a workout clock reads. */
    private const val MINUTE_THRESHOLD = 90

    fun increment(seconds: Int): Int = STEP

    /** Formats a hold/carry duration: "45s" under [MINUTE_THRESHOLD], "1:30" at or above. */
    fun format(seconds: Int): String {
        if (seconds < MINUTE_THRESHOLD) return "${seconds}s"
        val minutes = seconds / 60
        val remainderSeconds = seconds % 60
        return "$minutes:${remainderSeconds.toString().padStart(2, '0')}"
    }
}
