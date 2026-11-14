package cloud.trotter.log.strength.ui.day.receipt

import android.os.Parcelable
import cloud.trotter.log.strength.data.RunTrack
import cloud.trotter.log.strength.domain.model.SetKind
import kotlinx.parcelize.Parcelize

/** Separate saved state preserves the field order of existing receipt parcels. */
@Parcelize
data class ReceiptBackoffLabels(val sessionId: Long, val lines: List<ReceiptBackoffLine>) : Parcelable

@Parcelize
data class ReceiptBackoffLine(val groupIndex: Int, val rowIndex: Int, val lineIndex: Int) : Parcelable

internal fun receiptBackoffLabels(
    sessionId: Long,
    groupsWithTracks: List<Pair<ReceiptGroup, List<RunTrack>>>,
): ReceiptBackoffLabels = ReceiptBackoffLabels(sessionId, buildList {
    groupsWithTracks.forEachIndexed { groupIndex, (group, tracks) ->
        group.rows.forEachIndexed { rowIndex, row ->
            if (row.shape == ReceiptRow.Shape.TOP_SET) {
                val done = tracks[rowIndex].sets.filter { it.done }
                row.lines.forEachIndexed { lineIndex, line ->
                    if (lineIndex > 0 && line.setIndices.isNotEmpty() &&
                        line.setIndices.all { done[it].kind == SetKind.BACKOFF }) {
                        add(ReceiptBackoffLine(groupIndex, rowIndex, lineIndex))
                    }
                }
            }
        }
    }
})
