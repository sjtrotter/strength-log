package cloud.trotter.log.strength.ui.day.receipt

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Frozen parcels read only to discard pre-2c receipt keys at restore. */
@Parcelize data class ReceiptNextTime(val groupIndex: Int, val rowIndex: Int, val offerLb: Double?, val openingLb: Double) : Parcelable

@Parcelize data class ReceiptNextTimes(val sessionId: Long, val rows: List<ReceiptNextTime>) : Parcelable

/** Frozen rep-offer parcels from pre-2c receipts. */
@Parcelize data class ReceiptRepNextTimes(val sessionId: Long, val rows: List<ReceiptRepNextTime>) : Parcelable
@Parcelize data class ReceiptRepNextTime(val groupIndex: Int, val rowIndex: Int, val reps: Int) : Parcelable
