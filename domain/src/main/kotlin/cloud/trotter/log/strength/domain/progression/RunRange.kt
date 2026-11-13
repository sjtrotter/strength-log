package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure

/** A quality session's range (design §3.1.2), low end first. A step is one unit above the low end (§3.1): rounds fill
 *  first, then every rep's end grows by [unit]. [rep] is metres when [distance], else seconds. */
internal data class RunRange(
    val rounds: IntRange,
    val rep: IntRange,
    val unit: Int,
    val distance: Boolean,
    /** Between reps; null is equal to the rep ("equal easy"). */
    val recoverySeconds: Int?,
    /** Good band (steady and tempo work): a Hard rating is above it. */
    val goodBand: Boolean,
) {
    val top: Int get() = rounds.last - rounds.first + (rep.last - rep.first) / unit

    /** The block at [steps] (0..[top]): WORK sets hard, RECOVERY only between reps, no aims. */
    fun block(steps: Int, activity: CardioActivity): CardioBlock {
        require(steps in 0..top)
        val extra = rounds.last - rounds.first
        val n = rounds.first + minOf(steps, extra)
        val end = rep.first + maxOf(0, steps - extra) * unit
        return CardioBlock(activity, buildList {
            repeat(n) { index ->
                if (index > 0) add(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(recoverySeconds ?: end)))
                add(CardioSetSpec(CardioRole.WORK, if (distance) Measure.Distance(end) else Measure.Time(end), hard = true))
            }
        })
    }

    companion object {
        /** Design §3.1.2's table; null for LADDER, EASY and LONG. */
        fun of(session: RunSession): RunRange? = when (session) {
            RunSession.THRESHOLD -> RunRange(1..1, 1_200..1_800, RunCap.SECONDS_GRID, false, null, true)
            RunSession.TEMPO -> RunRange(1..1, 900..1_500, RunCap.SECONDS_GRID, false, null, true)
            RunSession.VO2MAX -> RunRange(5..6, 180..240, RunCap.SECONDS_GRID, false, null, false)
            RunSession.RACE_PACE -> RunRange(6..8, 400..400, RunCap.METRES_GRID, true, 120, false)
            RunSession.SHARPENING -> RunRange(3..4, 300..500, RunCap.METRES_GRID, true, 240, false)
            RunSession.TEST_INTERVALS -> RunRange(4..6, 400..800, RunCap.METRES_GRID, true, 120, false)
            RunSession.LADDER, RunSession.EASY, RunSession.LONG -> null
        }
    }
}
