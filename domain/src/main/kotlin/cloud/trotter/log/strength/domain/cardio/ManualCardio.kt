package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.progression.FinishedRule

/** A cardio session entered after it happened: one finished work set (#498).
 *  It has one reading, which is both readings at the end. */
object ManualCardio {
    const val MAX_SECONDS = 86_400
    const val MAX_METRES = 200_000

    fun result(seconds: Int, metres: Int?): CardioSetResult = FinishedRule.atEnd(CardioSetResult(
        CardioSetSpec(CardioRole.WORK, Measure.Time(seconds)), CardioSetStatus.FINISHED, seconds,
        metres, metres?.let { DistanceSource.MANUAL },
    ))

    fun summary(activity: CardioActivity, seconds: Int): CardioSummary = CardioSummary(
        mode = CardioBlocks.legacyModeOf(activity), hard = false,
        label = cardioActivityLabel(activity), seconds = seconds, stepsCompleted = 1,
    )
}
