package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.domain.cardio.ManualCardio
import cloud.trotter.log.strength.domain.model.CardioActivity
import kotlinx.parcelize.Parcelize

/** A cardio session typed after the fact, or one being edited in History (part B). Its own saved key. */
@Parcelize
data class CardioEntryDraft(
    val activity: CardioActivity,
    val startedAt: Long,
    val seconds: Int,
    val metresText: String = "",
    val note: String = "",
    /** False for a session of several sets: History edits its note and activity only. */
    val measuresEditable: Boolean = true,
) : Parcelable {
    val metres: Int? get() = metresText.toIntOrNull()?.takeIf { it > 0 }
    val completedAt: Long get() = startedAt + seconds * 1_000L
    fun valid(nowMillis: Long, completedAt: Long = this.completedAt): Boolean =
        seconds in 1..ManualCardio.MAX_SECONDS &&
            (metresText.isBlank() || (metresText.all(Char::isDigit) && (metres ?: 0) in 1..ManualCardio.MAX_METRES)) &&
            completedAt <= nowMillis
}
