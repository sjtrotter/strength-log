package cloud.trotter.log.strength.domain.model

import cloud.trotter.log.strength.domain.standards.TestRef

/** Required reps or seconds are typed by the user; no point table (#646). */
data class TestEventGoal(val ref: TestRef, val required: Int) {
    init { require(required in 1..3600) }
}

data class TestGoal(val events: List<TestEventGoal>, val byEpochDay: Long? = null) {
    init {
        require(events.isNotEmpty() && events.map { it.ref }.distinct().size == events.size)
        require(PlanGoalDate.isValid(byEpochDay))
    }

    fun forRef(ref: TestRef): TestEventGoal? = events.firstOrNull { it.ref == ref }
}
