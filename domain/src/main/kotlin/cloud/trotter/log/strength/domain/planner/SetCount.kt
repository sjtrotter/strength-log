package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.SetKind

/** Weekly sets and exposures per muscle over a rotation's strength days (design §3.1 invariants 3 to 5). */
object SetCount {
    /** A primary credits 1 per working set, a secondary 0.5; ramps are warm-ups and never count. */
    fun weekly(program: Program): Map<Muscle, Double> = buildMap {
        for (day in program.strengthDays) for (slot in day.exercises) {
            val sets = setKinds(slot).count { it != SetKind.RAMP }
            for (id in listOfNotNull(slot.exerciseId, slot.superset?.exerciseId)) {
                val entry = ExerciseLibrary.find(id) ?: continue
                for (muscle in entry.primary) put(muscle, getOrDefault(muscle, 0.0) + sets)
                for (muscle in entry.secondary) put(muscle, getOrDefault(muscle, 0.0) + sets * 0.5)
            }
        }
    }

    /** Strength days on which any slot or partner credits the muscle, primary or secondary. */
    fun exposures(program: Program): Map<Muscle, Int> = buildMap {
        for (day in program.strengthDays) {
            val muscles = day.exercises.flatMap { slot ->
                listOfNotNull(slot.exerciseId, slot.superset?.exerciseId).flatMap { id ->
                    ExerciseLibrary.find(id)?.let { it.primary + it.secondary }.orEmpty()
                }
            }.toSet()
            for (muscle in muscles) put(muscle, getOrDefault(muscle, 0) + 1)
        }
    }
}
