package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunSession
import kotlinx.serialization.Serializable

@Serializable internal data class CardioRuleDto(
    val ladderLevel: Int? = null,
    val session: String? = null,
    val steps: Int = 0,
    val credits: Int = 0,
    val alternate: String? = null,
    val longMetres: Int? = null,
    val longSeconds: Int? = null,
) {
    fun toDomain(): CardioRule = CardioRule(ladderLevel, session?.let(RunSession::valueOf), steps, credits,
        alternate?.let(RunSession::valueOf), longMetres, longSeconds)
}

object CardioRuleJson {
    fun encode(rule: CardioRule): String {
        val dto = CardioRuleDto(rule.ladderLevel, rule.session?.name, rule.steps, rule.credits, rule.alternate?.name,
            rule.longMetres, rule.longSeconds)
        dto.toDomain()
        return SetJson.encode(CardioRuleDto.serializer(), dto)
    }

    fun decode(json: String?): CardioRule? = json?.let { runCatching { read(it) }.getOrNull() }
    fun validate(json: String) { read(json) }
    private fun read(json: String): CardioRule = SetJson.decode(CardioRuleDto.serializer(), json).toDomain()
}
