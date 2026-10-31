package cloud.trotter.log.strength.data

fun minExercisesPerDay(hasCardio: Boolean): Int = if (hasCardio) 0 else 1
