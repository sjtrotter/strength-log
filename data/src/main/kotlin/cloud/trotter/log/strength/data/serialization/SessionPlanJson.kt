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
    /** Finish-time rows; null in snapshots saved before this count was recorded. */
    val liveSetCount: Int? = null,
    val note: String? = null,
    val offer: OfferDto? = null,
)

/** The change Finish recorded. Proposed rows and targets travel together for Ask acceptance. */
@Serializable
data class OfferDto(
    val fromLb: Double,
    val toLb: Double,
    val beforeSets: List<SetDto>? = null,
    val afterSets: List<SetDto>? = null,
    val beforeRuleJson: String? = null,
    val afterRuleJson: String? = null,
) {
    init {
        require(fromLb.isFinite() && toLb.isFinite() && fromLb >= 0.0 && toLb >= 0.0)
        require((beforeSets == null) == (afterSets == null))
        require((beforeRuleJson == null) == (afterRuleJson == null))
        beforeRuleJson?.let(SlotRuleJson::validate)
        afterRuleJson?.let(SlotRuleJson::validate)
        require((beforeSets == null) == (beforeRuleJson == null))
        (beforeSets.orEmpty() + afterSets.orEmpty()).forEach {
            require(it.weightLb.isFinite() && it.weightLb >= 0.0 && it.reps >= 0 && it.seconds >= 0)
            it.toDomain()
        }
    }
}

/** Codec for the prescriptions captured in a workout session. */
object SessionPlanJson {
    /** Encodes ordered track prescriptions. */
    fun encode(tracks: List<PlannedTrackDto>): String =
        SetJson.encode(ListSerializer(PlannedTrackDto.serializer()), tracks)

    /** Decodes ordered track prescriptions. */
    fun decode(text: String): List<PlannedTrackDto> =
        SetJson.decode(ListSerializer(PlannedTrackDto.serializer()), text)
}
