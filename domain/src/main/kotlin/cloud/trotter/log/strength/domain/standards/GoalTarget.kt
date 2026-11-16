package cloud.trotter.log.strength.domain.standards

/** A lift's typed goal (the plan's `goalsLb` entry), shown as its GOAL. */
data class GoalTarget(val lb: Double, val perHand: Boolean)
