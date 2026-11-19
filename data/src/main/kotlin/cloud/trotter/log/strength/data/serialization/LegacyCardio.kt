package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.cardio.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioBlock

object LegacyCardio {
    fun toBlock(cardioJson: String, recipe: V1Recipe): CardioBlock? {
        val suggestion = CardioDto.decode(cardioJson) ?: return null
        val activity = CardioBlocks.activityOf(recipe.cardioMode) ?: return null
        return CardioBlocks.fromLegacy(suggestion, recipe.fiveKGoal, activity)
    }
}
