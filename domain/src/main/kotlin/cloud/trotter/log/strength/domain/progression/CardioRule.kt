package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.ProgramDay

/** A cardio day's progression state, the cardio twin of [SlotRule], kept on [ProgramDay.cardioRule] and changed
 *  only by [Progression.cardioAfterSession]. The planner sets the ladder level, the session kind, the alternation
 *  and the long-run target; ranges step in [Progression.cardioAfterSession]; sprint volume comes in PR 5. */
data class CardioRule(
    /** The run/walk ladder level, 1 to [RunWalkLadder.TOP]; null when the day is not on the ladder. */
    val ladderLevel: Int?,
    /** What the day is for; null on ladder days and on every rule written before PR 4b. */
    val session: RunSession? = null,
    /** Units above the range's low end, shared with [alternate] (design §3.1). */
    val steps: Int = 0,
    /** Counted sessions toward the next unit, 0 or 1. */
    val credits: Int = 0,
    /** The kind a lone base-phase threshold day alternates with (§3.1.2). */
    val alternate: RunSession? = null,
    /** The long run's stop: [longMetres] stops a distance end; [longSeconds] stops a time end and, at the recent
     *  pace, a distance end. */
    val longMetres: Int? = null,
    val longSeconds: Int? = null,
) {
    init {
        require(ladderLevel == null || ladderLevel in 1..RunWalkLadder.TOP)
        require(ladderLevel == null || session == null)
        require(session != RunSession.LADDER)
        require(credits in 0..1)
        val range = session?.let(RunRange::of)
        require(credits == 0 || range != null)
        require(alternate == null || (range != null && alternate != session && RunRange.of(alternate) != null))
        val top = maxOf(range?.top ?: 0, alternate?.let(RunRange::of)?.top ?: 0)
        require(steps in 0..top)
        require(session == RunSession.LONG || (longMetres == null && longSeconds == null))
        require(session != RunSession.LONG || longMetres != null || longSeconds != null)
        require(longMetres == null || longMetres in 1..200_000)
        require(longSeconds == null || longSeconds in 1..86_400)
    }
}
