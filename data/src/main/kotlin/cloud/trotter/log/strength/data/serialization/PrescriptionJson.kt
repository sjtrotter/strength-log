package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import kotlinx.serialization.Serializable

/** Nullable fields let validation distinguish a missing target value from zero. */
@Serializable
internal data class PrescriptionDto(
    val type: String,
    val lb: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val addedLb: Double = 0.0,
    val repAim: Int? = null,
    val eachSide: Boolean = false,
    val resistance: String = "",
) {
    fun toDomain(): Prescription {
        require(resistance.length <= 40) { "resistance must be at most 40 characters" }
        lb?.let { require(it.isFinite() && it in 0.0..2000.0) { "lb must be 0..2000" } }
        require(addedLb.isFinite() && addedLb in 0.0..2000.0) { "addedLb must be 0..2000" }
        reps?.let { require(it in 0..999) { "reps must be 0..999" } }
        repAim?.let { require(it in 0..999) { "repAim must be 0..999" } }
        seconds?.let { require(it in 1..3600) { "seconds must be 1..3600" } }
        val target = when (type) {
            "loadReps" -> PrescribedTarget.LoadReps(requireNotNull(lb), requireNotNull(reps))
            "reps" -> PrescribedTarget.Reps(requireNotNull(reps))
            "hold" -> PrescribedTarget.Hold(requireNotNull(seconds), addedLb)
            "repsInTime" -> PrescribedTarget.RepsInTime(requireNotNull(seconds), repAim)
            else -> throw IllegalArgumentException("unknown prescription type '$type'")
        }
        return Prescription(target, eachSide, resistance)
    }
}

/** Stored rows degrade to generated slots on invalid payloads; backups fail validation. */
object PrescriptionJson {
    fun encode(prescription: Prescription): String {
        val dto = when (val target = prescription.target) {
            is PrescribedTarget.LoadReps -> PrescriptionDto("loadReps", lb = target.lb, reps = target.reps)
            is PrescribedTarget.Reps -> PrescriptionDto("reps", reps = target.reps)
            is PrescribedTarget.Hold -> PrescriptionDto("hold", seconds = target.seconds, addedLb = target.addedLb)
            is PrescribedTarget.RepsInTime -> PrescriptionDto("repsInTime", seconds = target.seconds, repAim = target.repAim)
        }.copy(eachSide = prescription.eachSide, resistance = prescription.resistance)
        dto.toDomain()
        return SetJson.encode(PrescriptionDto.serializer(), dto)
    }

    fun decode(json: String?): Prescription? = json?.let {
        runCatching { read(it) }.getOrNull()
    }

    /** Throws for malformed, unknown, incomplete or out-of-range portable targets. */
    fun validate(json: String) {
        read(json)
    }

    private fun read(json: String): Prescription =
        SetJson.decode(PrescriptionDto.serializer(), json).toDomain()
}
