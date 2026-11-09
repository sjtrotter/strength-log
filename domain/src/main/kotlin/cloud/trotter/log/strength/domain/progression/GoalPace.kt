package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.PlanGoal

/** Interval aims from a timed goal (design §3.1.2, Fitness test row): reps run 5 % faster than goal pace. */
object GoalPace {
    const val TEST_FACTOR = 0.95

    /** Seconds for [repMetres] at [goal]'s pace × [TEST_FACTOR], rounded to the nearest second once. */
    fun testAimSeconds(goal: PlanGoal.Timed, repMetres: Int): Int =
        Math.round(goal.underSeconds.toDouble() * repMetres / goal.metres * TEST_FACTOR).toInt()
}
