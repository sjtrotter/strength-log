package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioSuggestion

data class IntervalStep(val label: String, val seconds: Int, val hard: Boolean)

data class IntervalPlan(val steps: List<IntervalStep>) {
    val totalSeconds: Int get() = steps.sumOf { it.seconds }
}

/** Executable plans for the old wizard's cardio suggestions, kept for legacy cardio conversion and the watch: easy
 *  zone 2 pinned to 25 minutes, tempo to 20, and 5K intervals to 5 × 2 min hard / 2 min easy. */
object CardioIntervals {

    fun plan(suggestion: CardioSuggestion, fiveK: Boolean): IntervalPlan =
        when {
            !suggestion.hard -> IntervalPlan(
                steps = listOf(IntervalStep(label = "EASY", seconds = 1_500, hard = false)),
            )

            !fiveK -> IntervalPlan(
                steps = listOf(IntervalStep(label = "TEMPO", seconds = 1_200, hard = true)),
            )

            else -> IntervalPlan(
                steps = buildList {
                    add(IntervalStep(label = "WARM-UP", seconds = 300, hard = false))
                    repeat(5) {
                        add(IntervalStep(label = "HARD", seconds = 120, hard = true))
                        add(IntervalStep(label = "EASY", seconds = 120, hard = false))
                    }
                },
            )
        }
}
