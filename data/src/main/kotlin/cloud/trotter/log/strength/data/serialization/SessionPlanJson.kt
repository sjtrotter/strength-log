package cloud.trotter.log.strength.data.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Captured prescription for one archived run track. */
@Serializable
data class PlannedTrackDto(
    val position: Int,
    val exerciseId: String,
    val slot: String,
    val retired: Boolean = false,
    val sets: List<SetDto>,
)

/** Codec for the prescriptions captured in a workout session. */
object SessionPlanJson {
    /** Encodes ordered track prescriptions. */
    fun encode(tracks: List<PlannedTrackDto>): String =
        SetJson.encode(ListSerializer(PlannedTrackDto.serializer()), tracks)

    /** Decodes ordered track prescriptions. */
    fun decode(text: String): List<PlannedTrackDto> =
        SetJson.decode(ListSerializer(PlannedTrackDto.serializer()), text)
}
