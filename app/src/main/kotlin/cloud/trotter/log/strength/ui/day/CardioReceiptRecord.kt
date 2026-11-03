package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.records.CardioBests
import cloud.trotter.log.strength.domain.records.PrKind
import kotlinx.parcelize.Parcelize
import java.util.Locale

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

internal fun CardioRun.receiptRecords(standingBests: List<SavedCardioBest>): List<CardioReceiptRecord> {
    val activity = block.activity
    val standing = CardioBests.bests(activity, standingBests.map { it.toDomain() })
    return CardioBests.records(activity, resultSets, standing).map { record ->
        val name = when (val end = record.key.end) {
            is Measure.Distance -> formatRecordMetres(end.metres)
            is Measure.Time -> formatCardioTime(end.seconds)
        }
        fun value(result: CardioSetResult): String = when (record.key.end) {
            is Measure.Distance -> formatCardioTime(result.elapsedSeconds)
            is Measure.Time -> formatRecordMetres(requireNotNull(result.metres))
        }
        CardioReceiptRecord(name, value(record.best), value(record.previous), record.kind)
    }
}

private fun formatRecordMetres(metres: Int): String = String.format(Locale.US, "%,d m", metres)
