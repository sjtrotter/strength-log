package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.records.PrKind
import kotlinx.parcelize.Parcelize

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
