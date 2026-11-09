package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.progression.LayoffKind
import kotlinx.serialization.Serializable

/** How a run track opened (design §3.2 atStart): its layoff and whether the plan's light cycle shaped its rows. A
 *  normal opening is stored as null, never as a value. */
@Serializable
data class OpeningDto(val layoff: String = NONE, val factor: Double? = null, val light: Boolean = false) {
    /** Throws on an unknown kind, or a factor that is absent, present on another kind, or outside (0, 1). */
    fun layoffKind(): LayoffKind {
        if (layoff == SCALED) {
            require(factor != null && factor > 0.0 && factor < 1.0)
            return LayoffKind.Scaled(factor)
        }
        require(factor == null)
        return when (layoff) {
            NONE -> LayoffKind.None
            HOLD -> LayoffKind.Hold
            RECALIBRATE -> LayoffKind.Recalibrate
            else -> throw IllegalArgumentException("Unknown layoff kind: $layoff")
        }
    }

    companion object {
        const val NONE = "NONE"
        const val HOLD = "HOLD"
        const val SCALED = "SCALED"
        const val RECALIBRATE = "RECALIBRATE"

        /** Null for [LayoffKind.None] without [light]. */
        fun of(layoff: LayoffKind, light: Boolean): OpeningDto? = when (layoff) {
            LayoffKind.None -> if (light) OpeningDto(light = true) else null
            LayoffKind.Hold -> OpeningDto(HOLD, light = light)
            is LayoffKind.Scaled -> OpeningDto(SCALED, layoff.factor, light)
            LayoffKind.Recalibrate -> OpeningDto(RECALIBRATE, light = light)
        }
    }
}

object OpeningJson {
    fun encode(dto: OpeningDto): String = SetJson.encode(OpeningDto.serializer(), dto)

    /** Null for null or an unreadable value, which reads as a normal opening. */
    fun decode(json: String?): OpeningDto? = json?.let {
        runCatching { SetJson.decode(OpeningDto.serializer(), it).also { dto -> dto.layoffKind() } }.getOrNull()
    }

    /** Throws when [json] does not decode or [OpeningDto.layoffKind] throws. */
    fun validate(json: String) {
        SetJson.decode(OpeningDto.serializer(), json).layoffKind()
    }
}
