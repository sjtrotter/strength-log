package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure

/** Sprint rep lengths use a 10 m grid. */
private const val SPRINT_UNIT_METRES = 10

/** A quality or sprint session's range (design §3.1.2, §3.1.3), low end first. Run steps fill rounds before
 *  lengthening reps by [unit]; sprint steps add [stepMetres] to the total. [rep] is meters when [distance], else seconds. */
internal data class RunRange(
    val rounds: IntRange,
    val rep: IntRange,
    val unit: Int,
    val distance: Boolean,
    /** Between reps; null is equal to the rep ("equal easy"). */
    val recoverySeconds: Int?,
    /** Good band (steady and tempo work): a Hard rating is above it. */
    val goodBand: Boolean,
    /** Recovery seconds per meter of rep, in place of [recoverySeconds]: acceleration rests 1 min per 10 m (§3.1.3). */
    val recoveryPerMetre: Int? = null,
    /** WORK sets are hard; sprint tempo's are not (revision 30). */
    val hard: Boolean = true,
    /** Sprint steps add this many meters to the WORK total; null keeps run range stepping. */
    val stepMetres: Int? = null,
) {
    val top: Int get() = stepMetres?.let { (rounds.last * rep.last - rounds.first * rep.first) / it }
        ?: (rounds.last - rounds.first + (rep.last - rep.first) / unit)

    /** The block at [steps] (0..[top]): WORK sets [hard], RECOVERY only between reps, no aims. */
    fun block(steps: Int, activity: CardioActivity): CardioBlock {
        require(steps in 0..top)
        val ends = if (stepMetres != null) {
            val total = rounds.first * rep.first + steps * stepMetres
            val n = (total / rep.first).coerceIn(rounds)
            val grains = total / unit
            List(n) { index -> (grains / n + if (index < grains % n) 1 else 0) * unit }
        } else {
            val extra = rounds.last - rounds.first
            val n = rounds.first + minOf(steps, extra)
            List(n) { rep.first + maxOf(0, steps - extra) * unit }
        }
        return CardioBlock(activity, buildList {
            repeat(ends.size) { index ->
                if (index > 0) add(CardioSetSpec(CardioRole.RECOVERY,
                    Measure.Time(recoveryPerMetre?.let { it * ends[index - 1] } ?: recoverySeconds ?: ends[index])))
                add(CardioSetSpec(CardioRole.WORK, if (distance) Measure.Distance(ends[index]) else Measure.Time(ends[index]), hard = hard))
            }
        })
    }

    companion object {
        /** Design §3.1.2's and §3.1.3's tables; null for LADDER, EASY and LONG. */
        fun of(session: RunSession): RunRange? = when (session) {
            RunSession.THRESHOLD -> RunRange(1..1, 1_200..1_800, RunCap.SECONDS_GRID, false, null, true)
            RunSession.TEMPO -> RunRange(1..1, 900..1_500, RunCap.SECONDS_GRID, false, null, true)
            RunSession.VO2MAX -> RunRange(5..6, 180..240, RunCap.SECONDS_GRID, false, null, false)
            RunSession.RACE_PACE -> RunRange(6..8, 400..400, RunCap.METRES_GRID, true, 120, false)
            RunSession.SHARPENING -> RunRange(3..4, 300..500, RunCap.METRES_GRID, true, 240, false)
            RunSession.TEST_INTERVALS -> RunRange(4..6, 400..800, RunCap.METRES_GRID, true, 120, false)
            RunSession.ACCELERATION -> RunRange(4..8, 10..30, SPRINT_UNIT_METRES, true, null, false, recoveryPerMetre = 6, stepMetres = 10)
            RunSession.MAX_VELOCITY -> RunRange(3..5, 10..20, SPRINT_UNIT_METRES, true, 240, false, stepMetres = 10)
            RunSession.SPRINT_ENDURANCE -> RunRange(2..4, 100..150, SPRINT_UNIT_METRES, true, 600, false, stepMetres = 20)
            RunSession.SPRINT_TEMPO -> RunRange(6..6, 100..300, RunCap.METRES_GRID, true, 60, true, hard = false, stepMetres = 100)
            RunSession.LADDER, RunSession.EASY, RunSession.LONG -> null
        }
    }
}
