package io.github.sjtrotter.strengthlog.data.serialization

import io.github.sjtrotter.strengthlog.domain.model.CardioSuggestion
import kotlinx.serialization.Serializable

/** Persistence shape of a [CardioSuggestion] (the `program_day.cardioJson` column). */
@Serializable
data class CardioDto(
    val label: String,
    val detail: String,
    val hard: Boolean,
) {
    fun toDomain(): CardioSuggestion = CardioSuggestion(label = label, detail = detail, hard = hard)

    companion object {
        fun of(c: CardioSuggestion): CardioDto = CardioDto(c.label, c.detail, c.hard)

        fun encode(c: CardioSuggestion?): String? =
            c?.let { SetJson.encode(serializer(), of(it)) }

        fun decode(text: String?): CardioSuggestion? =
            text?.let { SetJson.decode(serializer(), it).toDomain() }
    }
}
