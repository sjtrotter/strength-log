package cloud.trotter.log.strength.domain.progression

/** What a ladder Finish reads (design revision 47): the session's local Start date and whether some 7 consecutive
 *  local days of the last 14 hold more than 3 family runs, this run included ([RunWalkLadder.busy]). Production
 *  always passes it on ladder days; pure tests may omit it to run the two-per-level rule alone. */
data class LadderContext(val todayEpochDay: Long, val busy: Boolean)
