package cloud.trotter.log.strength.domain.progression

/** What an easy run's growth reads at Finish (audit 2026-11-13 row 4): the stop from the session minutes
 *  ([cloud.trotter.log.strength.domain.planner.easyRunSeconds]) and the rotation, so a day grows at most once a rotation. */
data class EasyRunContext(val stopSeconds: Int, val cyclesCompleted: Int) {
    init { require(stopSeconds >= 60 && cyclesCompleted >= 0) }
}
