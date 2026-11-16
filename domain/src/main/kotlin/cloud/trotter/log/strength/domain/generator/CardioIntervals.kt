package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.CardioSuggestion

data class CardioStep(val label: String, val seconds: Int, val hard: Boolean)

data class CardioPlan(val steps: List<CardioStep>) {
    val totalSeconds: Int get() = steps.sumOf { it.seconds }
}

/** Executable plans for the old wizard's cardio suggestions, kept for legacy cardio conversion and the watch: easy
 *  zone 2 pinned to 25 minutes, tempo to 20, and 5K intervals to 5 × 2 min hard / 2 min easy. */
object CardioIntervals {

    fun plan(suggestion: CardioSuggestion, fiveK: Boolean): CardioPlan =
        when {
            !suggestion.hard -> CardioPlan(
                steps = listOf(CardioStep(label = "EASY", seconds = 1_500, hard = false)),
            )

            !fiveK -> CardioPlan(
                steps = listOf(CardioStep(label = "TEMPO", seconds = 1_200, hard = true)),
            )

            else -> CardioPlan(
                steps = buildList {
                    add(CardioStep(label = "WARM-UP", seconds = 300, hard = false))
                    repeat(5) {
                        add(CardioStep(label = "HARD", seconds = 120, hard = true))
                        add(CardioStep(label = "EASY", seconds = 120, hard = false))
                    }
                },
            )
        }
}
