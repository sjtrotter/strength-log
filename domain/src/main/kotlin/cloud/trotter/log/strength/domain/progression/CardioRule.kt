package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.ProgramDay

/** A cardio day's progression state, the cardio twin of [SlotRule], kept on [ProgramDay.cardioRule] and changed
 *  only by [Progression.cardioAfterSession]. The planner sets the ladder level (PR 4a); quality alternation, ranges
 *  and the long-run target come in PR 4b, sprint volume in PR 5. */
data class CardioRule(
    /** The run/walk ladder level, 1 to [RunWalkLadder.TOP]; null when the day is not on the ladder. */
    val ladderLevel: Int?,
) {
    init { require(ladderLevel == null || ladderLevel in 1..RunWalkLadder.TOP) }
}
