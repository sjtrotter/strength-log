package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.progression.CardioRule
import kotlinx.serialization.Serializable

@Serializable internal data class CardioRuleDto(val ladderLevel: Int? = null) {
    fun toDomain(): CardioRule = CardioRule(ladderLevel)
}

object CardioRuleJson {
    fun encode(rule: CardioRule): String {
        val dto = CardioRuleDto(rule.ladderLevel)
        dto.toDomain()
        return SetJson.encode(CardioRuleDto.serializer(), dto)
    }

    fun decode(json: String?): CardioRule? = json?.let { runCatching { read(it) }.getOrNull() }
    fun validate(json: String) { read(json) }
    private fun read(json: String): CardioRule = SetJson.decode(CardioRuleDto.serializer(), json).toDomain()
}
