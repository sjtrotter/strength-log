package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.glance.GlanceLines
import kotlinx.parcelize.Parcelize
import kotlin.math.roundToInt

/**
 * A frozen receipt of the session Finish already committed. Saved in
 * [androidx.lifecycle.SavedStateHandle], including the new top sets computed
 * once at Finish, so rotation and process death keep the moment without ever
 * recomputing it from history. Dismissing it leaves the saved session intact.
 *
 * [sessionId] is the live handle for the shared
 * [cloud.trotter.log.strength.ui.log.share.ShareCardService] and session note.
 */
@Parcelize
data class SessionReceipt(
    val sessionId: Long,
    val dayId: String,
    /** The completed day's accent, even after the rotation advances. */
    val dayIndex: Int,
    val completedAt: Long,
    val minutes: Int?,
    /** Rounds actually ticked, matching the workout header ([Slot.isRound]). */
    val setCount: Int,
    val completedSetCount: Int,
    val totalSetCount: Int,
    val newTopSets: List<CascadeLift>,
    /** Next day's letter and title as authored; absent when no successor resolves. */
    val nextDayLine: String?,
    val note: String = "",
    val cardio: CardioRun? = null,
) : Parcelable

object SessionReceiptBuilder {

    /**
     * Reads back the committed session. [sessionSets] is the same fetch used to
     * compute [newTopSets]. Counting completed rounds ([Slot.isRound]) keeps
     * supersets consistent with the workout header, Today and the watch.
     * [nextDayId] may be absent if the completed day left the program at Finish.
     */
    fun from(
        sessionId: Long,
        dayId: String,
        dayIndex: Int,
        startedAt: Long?,
        completedAt: Long,
        completedSetCount: Int,
        totalSetCount: Int,
        sessionSets: List<SessionSetEntity>,
        newTopSets: List<CascadeLift>,
        nextDayId: String?,
        nextDayTitle: String,
    ): SessionReceipt = SessionReceipt(
        sessionId = sessionId,
        dayId = dayId,
        dayIndex = dayIndex,
        completedAt = completedAt,
        minutes = startedAt?.let { ((completedAt - it) / 60_000.0).roundToInt().coerceAtLeast(1) },
        setCount = sessionSets.count { it.done && Slot.isRound(it.slot) },
        completedSetCount = completedSetCount,
        totalSetCount = totalSetCount,
        newTopSets = newTopSets,
        nextDayLine = nextDayId?.let { GlanceLines.dayLine(it, nextDayTitle) },
    )
}
