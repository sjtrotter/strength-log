package cloud.trotter.log.strength.domain.sync

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import kotlinx.serialization.Serializable

/** Enum names on the wire; measure values are seconds or metres, never inferred. */
@Serializable
data class WatchCardioSetSpec(
    val role: String,
    val endKind: String,
    val endValue: Int,
    val aimKind: String? = null,
    val aimValue: Int? = null,
    val hard: Boolean = false,
) {
    fun toDomain(): CardioSetSpec {
        require((aimKind == null) == (aimValue == null))
        return CardioSetSpec(CardioRole.valueOf(role), measure(endKind, endValue),
            aimKind?.let { measure(it, requireNotNull(aimValue)) }, hard)
    }
}

@Serializable
data class WatchCardioBlock(val activity: String, val sets: List<WatchCardioSetSpec>) {
    fun toDomain(): CardioBlock = CardioBlock(CardioActivity.valueOf(activity), sets.map { it.toDomain() })
}

@Serializable
data class WatchCardioSetResult(
    val spec: WatchCardioSetSpec,
    val status: String,
    val elapsedSeconds: Int,
    val metres: Int? = null,
    val distanceSource: String? = null,
) {
    fun toDomain(): CardioSetResult = CardioSetResult(spec.toDomain(), CardioSetStatus.valueOf(status),
        elapsedSeconds, metres, distanceSource?.let(DistanceSource::valueOf))
}

fun CardioBlock.toWatch(): WatchCardioBlock = WatchCardioBlock(activity.name, sets.map { it.toWatch() })

fun CardioSetSpec.toWatch(): WatchCardioSetSpec = WatchCardioSetSpec(
    role.name, end.kind(), end.value(), aim?.kind(), aim?.value(), hard,
)

private fun Measure.kind(): String = when (this) {
    is Measure.Time -> "TIME"
    is Measure.Distance -> "DISTANCE"
}

private fun Measure.value(): Int = when (this) {
    is Measure.Time -> seconds
    is Measure.Distance -> metres
}

private fun measure(kind: String, value: Int): Measure = when (kind) {
    "TIME" -> Measure.Time(value)
    "DISTANCE" -> Measure.Distance(value)
    else -> error("Unknown cardio measure: $kind")
}
