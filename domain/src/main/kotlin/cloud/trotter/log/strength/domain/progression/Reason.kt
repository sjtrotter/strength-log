package cloud.trotter.log.strength.domain.progression

/** Why a slot changed (or didn't) after a session; the receipt's "Why" line reads it. The planner adds its own
 *  reasons in PR 3. */
sealed interface Reason {
    data object NoSets : Reason
    data object RoughDay : Reason
    data object LightCycle : Reason
    data object Layoff : Reason
    data object Calibrating : Reason
    data object Calibrated : Reason
    data object Lowered : Reason
    data object AllMade : Reason
    data object EasySession : Reason
    data object Streak : Reason
    data object MaxSet : Reason
    data object RepMiss : Reason
    data object Stall : Reason
    data object EffortMiss : Reason
    data object Incomplete : Reason
    data object AddWeight : Reason
    data object Timed : Reason
}
