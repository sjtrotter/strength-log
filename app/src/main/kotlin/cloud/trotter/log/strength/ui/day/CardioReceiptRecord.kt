package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.records.PrKind
import kotlinx.parcelize.Parcelize

/** Read-only fallback for bundles saved before #526's fix; new snapshots use [SavedCardioStanding]. */
@Parcelize
data class SavedCardioBest(
    val specJson: String,
    val elapsedSeconds: Int,
    val metres: Int?,
) : Parcelable {
    fun toDomain(): CardioSetResult = CardioSetResult(
        CardioBlockJson.decodeSet(specJson), CardioSetStatus.FINISHED, elapsedSeconds,
        metres, metres?.let { DistanceSource.MANUAL },
    )

    companion object {
        fun from(result: CardioSetResult): SavedCardioBest = SavedCardioBest(
            CardioBlockJson.encodeSet(result.spec), result.elapsedSeconds, result.metres,
        )
    }
}

@Parcelize
data class CardioReceiptRecord(
    val name: String,
    val value: String,
    val previous: String,
    val kind: PrKind,
) : Parcelable

/** A standing best with its reading at the planned end (#526); SavedCardioBest dropped it. */
@Parcelize
data class SavedCardioStanding(
    val specJson: String,
    val elapsedSeconds: Int,
    val metres: Int?,
    val metresAtEnd: Int?,
    val secondsAtEnd: Int?,
    val readingSource: String?,
) : Parcelable {
    fun toDomain(): CardioSetResult = CardioSetResult(
        CardioBlockJson.decodeSet(specJson), CardioSetStatus.FINISHED, elapsedSeconds,
        metres, metres?.let { DistanceSource.MANUAL }, metresAtEnd, secondsAtEnd,
        readingSource?.let(ReadingSource::valueOf),
    )

    companion object {
        fun from(result: CardioSetResult): SavedCardioStanding = SavedCardioStanding(
            CardioBlockJson.encodeSet(result.spec), result.elapsedSeconds, result.metres,
            result.metresAtEnd, result.secondsAtEnd, result.readingSource?.name,
        )
    }
}
