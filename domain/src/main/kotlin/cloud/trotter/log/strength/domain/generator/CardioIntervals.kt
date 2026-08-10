package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.CardioSuggestion

data class CardioStep(val label: String, val seconds: Int, val hard: Boolean)

data class CardioPlan(val steps: List<CardioStep>) {
    val totalSeconds: Int get() = steps.sumOf { it.seconds }
}

/**
 * Executable plans derived from [CardioPlanner]'s prose, which remains the
 * single source of truth. Easy Zone 2 deliberately pins the prose's 20–30
 * minute band to its 25-minute midpoint. Hard tempo preserves its stated 20
 * minutes, and hard 5k intervals deliberately pin the stated 4–6 repeats to 5.
 */
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
