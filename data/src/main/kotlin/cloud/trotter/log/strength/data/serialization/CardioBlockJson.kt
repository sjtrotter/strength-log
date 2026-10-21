package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import kotlinx.serialization.Serializable

@Serializable
internal data class MeasureDto(
    val type: String,
    val seconds: Int? = null,
    val metres: Int? = null,
) {
    fun toDomain(): Measure = when (type) {
        "time" -> {
            require(metres == null) { "time must not include metres" }
            Measure.Time(requireNotNull(seconds))
        }
        "distance" -> {
            require(seconds == null) { "distance must not include seconds" }
            Measure.Distance(requireNotNull(metres))
        }
        else -> throw IllegalArgumentException("unknown measure type '$type'")
    }

    companion object {
        fun of(measure: Measure): MeasureDto = when (measure) {
            is Measure.Time -> MeasureDto("time", seconds = measure.seconds)
            is Measure.Distance -> MeasureDto("distance", metres = measure.metres)
        }
    }
}

@Serializable
internal data class CardioSetSpecDto(
    val role: String,
    val end: MeasureDto,
    val aim: MeasureDto? = null,
    val hard: Boolean = false,
) {
    fun toDomain(): CardioSetSpec = CardioSetSpec(
        CardioRole.valueOf(role), end.toDomain(), aim?.toDomain(), hard,
    )

    companion object {
        fun of(set: CardioSetSpec): CardioSetSpecDto = CardioSetSpecDto(
            set.role.name, MeasureDto.of(set.end), set.aim?.let(MeasureDto::of), set.hard,
        )
    }
}

@Serializable
internal data class CardioBlockDto(val activity: String, val sets: List<CardioSetSpecDto>) {
    fun toDomain(): CardioBlock = CardioBlock(CardioActivity.valueOf(activity), sets.map { it.toDomain() })
}

/** Invalid stored blocks become null; backup validation reports the failure. */
object CardioBlockJson {
    fun encode(block: CardioBlock): String {
        val dto = CardioBlockDto(block.activity.name, block.sets.map(CardioSetSpecDto::of))
        dto.toDomain()
        return SetJson.encode(CardioBlockDto.serializer(), dto)
    }

    fun decode(json: String?): CardioBlock? = json?.let { runCatching { read(it) }.getOrNull() }

    /** Throws for malformed, unknown, incomplete or out-of-range blocks. */
    fun validate(json: String) {
        read(json)
    }

    private fun read(json: String): CardioBlock =
        SetJson.decode(CardioBlockDto.serializer(), json).toDomain()
}
