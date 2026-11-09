package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure

/** The run/walk ladder (design §3.1.2), a practice table. A level runs [Level.runSeconds] then walks
 *  [Level.walkSeconds], [Level.rounds] times; levels 10 to 12 are one continuous run. */
object RunWalkLadder {
    data class Level(val runSeconds: Int, val walkSeconds: Int, val rounds: Int)

    val LEVELS: List<Level> = listOf(
        Level(60, 120, 8), Level(90, 120, 8), Level(120, 90, 7), Level(180, 90, 6), Level(240, 60, 6), Level(300, 60, 5),
        Level(480, 60, 3), Level(600, 60, 2), Level(900, 60, 2), Level(1200, 0, 1), Level(1500, 0, 1), Level(1800, 0, 1),
    )

    /** The last level; finishing it rated Easy leaves the ladder. */
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
