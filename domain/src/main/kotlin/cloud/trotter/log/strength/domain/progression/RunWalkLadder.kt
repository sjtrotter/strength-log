package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure

/** Practice: two counted Easy finishes per level; Good, no word or unfinished keeps credit; Hard or Pain drops and clears it; dated progress counts at most three in seven days and holds busy level-ups a week (design revision 47). */
object RunWalkLadder {
    data class Level(val runSeconds: Int, val walkSeconds: Int, val rounds: Int) {
        /** Planned WORK seconds of one session at this level. */
        val runningSeconds: Int get() = runSeconds * rounds
    }

    /** Owner 2026-11-17; practice, research §4. */
    const val EASY_PER_LEVEL = 2
    /** Owner 2026-11-17; practice: Couch-to-5K and GRONORUN run three sessions a week (R46). */
    const val COUNTED_PER_WEEK = 3
    /** Practice, sized by simulation (design §3); no outcome study tests it. */
    const val LEVEL_UP_DAYS = 7
    /** Practice, sized by simulation (design §3); no outcome study tests it. */
    const val BUSY_RUNS_IN_7_DAYS = 3
    /** Practice, sized by simulation (design §3); no outcome study tests it. */
    const val LOOK_BACK_DAYS = 14

    /** Practice (design revision 47, research.md §5): level 1 is GRONORUN's week-1 dose with Couch-to-5K's walk;
     *  no table, cap or guard here has a shown injury or drop-out benefit. */
    val LEVELS: List<Level> = listOf(
        Level(60, 90, 10), Level(75, 90, 9), Level(105, 90, 7), Level(135, 90, 6), Level(180, 90, 5), Level(200, 90, 5),
        Level(270, 90, 4), Level(300, 90, 4), Level(450, 90, 3), Level(720, 90, 2), Level(1620, 0, 1), Level(1800, 0, 1),
    )

    /** Whether some 7 consecutive local days within todayEpochDay − 13..todayEpochDay hold more than
     *  [BUSY_RUNS_IN_7_DAYS] of [runEpochDays] (the family's runs by local Start date, this run included). */
    fun busy(runEpochDays: List<Long>, todayEpochDay: Long): Boolean =
        (todayEpochDay - LOOK_BACK_DAYS + 1..todayEpochDay - 6).any { start ->
            runEpochDays.count { it in start..start + 6 } > BUSY_RUNS_IN_7_DAYS
        }

    /** The last level; its second counted Easy finish leaves the ladder. */
    val TOP: Int get() = LEVELS.size

    /** Level [level]'s block: per round a WORK run, then a RECOVERY walk when the level has one; nothing hard, no
     *  warm-up. */
    fun block(level: Int, activity: CardioActivity): CardioBlock {
        require(level in 1..TOP)
        val step = LEVELS[level - 1]
        return CardioBlock(activity, buildList {
            repeat(step.rounds) {
                add(CardioSetSpec(CardioRole.WORK, Measure.Time(step.runSeconds)))
                if (step.walkSeconds > 0) add(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(step.walkSeconds)))
            }
        })
    }
}
