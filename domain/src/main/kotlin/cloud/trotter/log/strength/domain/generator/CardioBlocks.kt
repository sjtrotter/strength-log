package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Measure

/** Adapts the shipped interval plan without changing its steps or duration. */
object CardioBlocks {
    fun fromLegacy(suggestion: CardioSuggestion, fiveK: Boolean, activity: CardioActivity): CardioBlock {
        val steps = CardioIntervals.plan(suggestion, fiveK).steps
        return CardioBlock(activity, steps.mapIndexed { index, step ->
            CardioSetSpec(
                role = when {
                    step.label == "WARM-UP" -> CardioRole.WARMUP
                    step.hard -> CardioRole.WORK
                    index > 0 && steps[index - 1].hard -> CardioRole.RECOVERY
                    else -> CardioRole.WORK
                },
                end = Measure.Time(step.seconds),
                hard = step.hard,
            )
        })
    }

    /** LOW_IMPACT is the wizard's "bike or elliptical" choice, represented as a
     *  stationary bike here. The legacy Health Connect mapping of old rows is unchanged.
     *  NONE has no activity. */
    fun activityOf(mode: CardioMode): CardioActivity? = when (mode) {
        CardioMode.OUTDOOR_RUN -> CardioActivity.ROAD_RUN
        CardioMode.TREADMILL -> CardioActivity.TREADMILL_RUN
        CardioMode.LOW_IMPACT -> CardioActivity.STATIONARY_BIKE
        CardioMode.NONE -> null
    }
}
