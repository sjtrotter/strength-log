package cloud.trotter.log.strength.data.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One parked track of a slot: which slot name, which exercise it was for, and its working rows verbatim. */
@Serializable
data class ParkedTrackDto(val slot: String, val exerciseId: String, val setsJson: String)

object ParkedLogsJson {
    fun encode(tracks: List<ParkedTrackDto>): String =
        SetJson.encode(ListSerializer(ParkedTrackDto.serializer()), tracks)

    fun decode(text: String): List<ParkedTrackDto> =
        SetJson.decode(ListSerializer(ParkedTrackDto.serializer()), text)
}
