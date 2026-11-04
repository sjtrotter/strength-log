package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.db.entity.Slot
import kotlinx.parcelize.Parcelize

/** Layout frozen. Exists only so a bundle written before receipt v7 can be read at restore.
 * Removed by `SessionFinisher.init`. Delete after two releases that no longer write it. */
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

/** Layout frozen. Exists only so a bundle written before receipt v7 can be read at restore.
 * Removed by `SessionFinisher.init`. Delete after two releases that no longer write it. */
@Parcelize
data class CascadeLift(
    val name: String,
    val metDisplay: String,
    val newDisplay: String,
) : Parcelable

