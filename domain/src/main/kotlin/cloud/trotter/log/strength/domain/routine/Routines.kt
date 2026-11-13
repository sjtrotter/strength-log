package cloud.trotter.log.strength.domain.routine

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.library.muscleCredit
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Equipment

/** One exercise of the day with its working sets (ramps excluded); a superset partner is its own entry. */
data class CreditedExercise(val entry: ExerciseEntry, val workingSets: Int)

/** Builds the suggested warm-up and cool-down for a day (#574). Pure and deterministic. */
object Routines {
    const val WARM_UP_MIN_DYNAMIC = 4
    const val WARM_UP_MAX_DYNAMIC = 5
    const val COOL_DOWN_MIN_SECONDS = 180
    const val COOL_DOWN_MAX_SECONDS = 300

    fun warmUp(work: List<CreditedExercise>, cardio: CardioBlock?, equipment: Set<Equipment>): List<Move> {
        val day = day(work, cardio)
        val ranked = rankedMuscles(work, cardio)
        val pool = Move.entries.filter { it.kind == MoveKind.DYNAMIC && day in it.days && it.available(equipment) }
        val chosen = mutableListOf<Move>()
        for (muscle in ranked) {
            if (chosen.size == WARM_UP_MAX_DYNAMIC) break
            if (chosen.any { muscle in it.muscles }) continue
            pool.firstOrNull { it !in chosen && muscle in it.muscles }?.let(chosen::add)
        }
        for (move in pool.byRank(ranked)) {
            if (chosen.size >= WARM_UP_MIN_DYNAMIC) break
            if (move !in chosen) chosen.add(move)
        }
        val general = if (day != RoutineDay.LIFT && cardio?.sets?.firstOrNull()?.role == CardioRole.WARMUP) null
            else Move.entries.firstOrNull { it.kind == MoveKind.GENERAL && day in it.days && it.available(equipment) }
        return listOfNotNull(general) + chosen
    }

    fun coolDown(work: List<CreditedExercise>, cardio: CardioBlock?, equipment: Set<Equipment>): List<Move> {
        val ranked = rankedMuscles(work, cardio)
        val pool = Move.entries.filter { it.kind == MoveKind.STRETCH && it.available(equipment) }
        val chosen = mutableListOf<Move>()
        var total = 0
        fun add(move: Move) {
            val duration = seconds(listOf(move))
            if (total + duration <= COOL_DOWN_MAX_SECONDS) {
                chosen.add(move)
                total += duration
            }
        }
        for (muscle in ranked) {
            if (chosen.any { muscle in it.muscles }) continue
            pool.firstOrNull { it !in chosen && muscle in it.muscles }?.let(::add)
        }
        for (move in pool.byRank(ranked)) {
            if (total >= COOL_DOWN_MIN_SECONDS) break
            if (move !in chosen) add(move)
        }
        return chosen
    }

    /** Total time of the moves' time doses; a per-side time counts twice. Reps doses count nothing. */
    fun seconds(moves: List<Move>): Int = moves.sumOf { move ->
        when (val dose = move.dose) {
            is MoveDose.Reps -> 0
            is MoveDose.Time -> dose.seconds * if (dose.perSide) 2 else 1
        }
    }

    internal fun rankedMuscles(work: List<CreditedExercise>, cardio: CardioBlock?): List<Muscle> {
        val ranked = if (work.isNotEmpty()) {
            val credits = mutableMapOf<Muscle, Double>()
            for ((entry, sets) in work) entry.muscleCredit(sets).forEach { (muscle, credit) ->
                credits[muscle] = credits.getOrDefault(muscle, 0.0) + credit
            }
            credits.filterValues { it > 0 }.entries
                .sortedWith(compareByDescending<Map.Entry<Muscle, Double>> { it.value }.thenBy { it.key.ordinal })
                .map { it.key }
        } else cardio?.let {
            when (CardioFamily.of(it.activity)) {
                CardioFamily.RUN, CardioFamily.WALK -> listOf(Muscle.HAMSTRINGS, Muscle.CALVES, Muscle.QUADS, Muscle.GLUTES)
                CardioFamily.RIDE, CardioFamily.ELLIPTICAL -> listOf(Muscle.QUADS, Muscle.GLUTES, Muscle.HAMSTRINGS, Muscle.CALVES)
                CardioFamily.ROW -> listOf(Muscle.QUADS, Muscle.LATS, Muscle.UPPER_BACK, Muscle.GLUTES, Muscle.HAMSTRINGS)
            }
        }.orEmpty()
        return ranked.ifEmpty { WHOLE_BODY }
    }

    private fun day(work: List<CreditedExercise>, cardio: CardioBlock?): RoutineDay =
        if (work.isNotEmpty() || cardio == null) RoutineDay.LIFT else when (CardioFamily.of(cardio.activity)) {
            CardioFamily.RUN, CardioFamily.WALK -> RoutineDay.RUN
            CardioFamily.RIDE, CardioFamily.ROW, CardioFamily.ELLIPTICAL -> RoutineDay.CARDIO
        }

    private fun Move.available(equipment: Set<Equipment>) = this.equipment.all { it in equipment }

    private fun List<Move>.byRank(ranked: List<Muscle>): List<Move> = sortedBy { move ->
        ranked.indexOfFirst { it in move.muscles }.takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

    private val WHOLE_BODY = listOf(Muscle.QUADS, Muscle.GLUTES, Muscle.HAMSTRINGS, Muscle.CHEST,
        Muscle.UPPER_BACK, Muscle.FRONT_DELT, Muscle.CORE)
}
