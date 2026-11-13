package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.PlanGoal

/** Rep aims at a race pace (design §3.1.2); a test's reps run 5 % faster than goal pace. */
object GoalPace {
    const val TEST_FACTOR = 0.95

    /** Seconds for [repMetres] at [goal]'s pace × [TEST_FACTOR], rounded to the nearest second once. */
    fun testAimSeconds(goal: PlanGoal.Timed, repMetres: Int): Int =
        aimSeconds(goal.underSeconds.toDouble(), goal.metres.toDouble(), repMetres, TEST_FACTOR)

    /** Seconds for [repMetres] at the race's pace × [factor], rounded once, with a one-second minimum. */
    fun aimSeconds(raceSeconds: Double, raceMetres: Double, repMetres: Int, factor: Double = 1.0): Int =
        maxOf(1, Math.round(raceSeconds * repMetres / raceMetres * factor).toInt())
}
