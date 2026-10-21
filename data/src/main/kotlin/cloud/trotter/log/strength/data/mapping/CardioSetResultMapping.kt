package cloud.trotter.log.strength.data.mapping

import cloud.trotter.log.strength.data.db.entity.CardioSetResultEntity
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource

fun CardioSetResultEntity.toDomain(): CardioSetResult = CardioSetResult(
    spec = CardioBlockJson.decodeSet(specJson),
    status = CardioSetStatus.valueOf(status),
    elapsedSeconds = elapsedSeconds,
    metres = metres,
    distanceSource = distanceSource?.let(DistanceSource::valueOf),
)

/** Invalid stored rows are omitted at the read boundary; backup validation stays strict. */
fun CardioSetResultEntity.toDomainOrNull(): CardioSetResult? = try {
    toDomain()
} catch (_: IllegalArgumentException) {
    null
}

fun CardioSetResult.toEntity(sessionId: Long, position: Int): CardioSetResultEntity = CardioSetResultEntity(
    cardioSessionId = sessionId,
    position = position,
    specJson = CardioBlockJson.encodeSet(spec),
    status = status.name,
    elapsedSeconds = elapsedSeconds,
    metres = metres,
    distanceSource = distanceSource?.name,
)
