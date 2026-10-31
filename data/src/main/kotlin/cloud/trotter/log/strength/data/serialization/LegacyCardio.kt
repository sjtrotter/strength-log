package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioBlock

object LegacyCardio {
    fun toBlock(cardioJson: String, answers: WizardAnswers): CardioBlock? {
        val suggestion = CardioDto.decode(cardioJson) ?: return null
        val activity = CardioBlocks.activityOf(answers.cardio.mode) ?: return null
        return CardioBlocks.fromLegacy(suggestion, answers.cardio.fiveKGoal, activity)
    }
}
