package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.glance.GlanceLines
import kotlin.math.roundToInt

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
