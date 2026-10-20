package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.PlanOrigin

/** Generated days keep the §8.3 floor; authored plans may prescribe a single move. */
fun minExercisesPerDay(origin: PlanOrigin): Int = when (origin) {
    PlanOrigin.GENERATED -> 3
    PlanOrigin.AUTHORED -> 1
}
