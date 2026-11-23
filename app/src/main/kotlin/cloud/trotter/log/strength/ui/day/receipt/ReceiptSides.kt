package cloud.trotter.log.strength.ui.day.receipt

import android.os.Parcelable
import cloud.trotter.log.strength.data.RunTrack
import kotlinx.parcelize.Parcelize

/** Its own saved key preserves existing receipt parcel shapes (#644). */
@Parcelize
data class ReceiptSides(val sessionId: Long, val sets: List<ReceiptSideSet>, val gaps: List<ReceiptSideGap>) : Parcelable

/** [setIndex] indexes [ReceiptRow.sets]. */
@Parcelize
data class ReceiptSideSet(val groupIndex: Int, val rowIndex: Int, val setIndex: Int, val left: ReceiptSet, val right: ReceiptSet) : Parcelable

@Parcelize
data class ReceiptSideGap(val groupIndex: Int, val rowIndex: Int, val left: Boolean) : Parcelable

internal fun receiptSideSets(groupsWithTracks: List<Pair<ReceiptGroup, List<RunTrack>>>): List<ReceiptSideSet> = buildList {
    groupsWithTracks.forEachIndexed { groupIndex, (group, tracks) ->
        group.rows.forEachIndexed { rowIndex, _ ->
            tracks[rowIndex].sets.filter { it.done }.forEachIndexed { setIndex, set ->
                set.sides?.let {
                    add(ReceiptSideSet(groupIndex, rowIndex, setIndex,
                        ReceiptSet(it.left.weightLb, it.left.reps, it.left.seconds),
                        ReceiptSet(it.right.weightLb, it.right.reps, it.right.seconds)))
                }
            }
        }
    }
}
