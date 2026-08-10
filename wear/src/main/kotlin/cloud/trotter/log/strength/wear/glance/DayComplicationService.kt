package cloud.trotter.log.strength.wear.glance

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.android.gms.wearable.Wearable
import cloud.trotter.log.strength.wear.MainActivity
import cloud.trotter.log.strength.wear.data.SnapshotItem

/**
 * Today's day letter and set progress, on the watch face (glance-surfaces brief §2).
 *
 * RANGED_VALUE is the point of it: the face draws the dial's outer ring for us, in
 * its own style. SHORT_TEXT is the fallback for slots that can't take a range —
 * the letter, with "12/21" as the title. We send data, never decoration: no images,
 * no colors, nothing that would fight the face it lands on.
 *
 * Every request re-reads the persisted DataItem, so the answer is correct from a
 * cold process with the phone out of range. Data pushes still refresh immediately;
 * activation and civil-day/timezone broadcasts cover visibility and rollover.
 */
class DayComplicationService : SuspendingComplicationDataSourceService() {

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        ComplicationDataSourceUpdateRequester
            .create(this, ComponentName(this, DayComplicationService::class.java))
            .requestUpdate(complicationInstanceId)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        complicationData(request.complicationType, DayGlance.of(readSnapshot()))

    /** What face editors show while the lifter is picking a complication. */
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        complicationData(type, PREVIEW)

    private suspend fun readSnapshot() = try {
        CivilDayFreshness.scheduleNextRollover(this)
        SnapshotItem.latest(Wearable.getDataClient(this))
    } catch (e: Exception) {
        // No Data Layer on this node, or Play Services said no — an empty glance is
        // a truthful answer, and a thrown one would blank the slot with an error.
        Log.w(TAG, "reading the snapshot for a complication failed", e)
        null
    }

    private fun complicationData(type: ComplicationType, glance: DayGlance): ComplicationData? =
        when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                glance.rangeValue,
                0f,
                glance.rangeMax,
                text(glance.contentDescription(glanceCopy())),
            )
                .setText(text(glance.shortText(glanceCopy())))
                .setTapAction(openApp())
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text(glance.shortText(glanceCopy())),
                text(glance.contentDescription(glanceCopy())),
            )
                .setTapAction(openApp())
                .also {
                    val ratio = glance.ratioText(glanceCopy())
                    if (ratio.isNotBlank()) it.setTitle(text(ratio))
                }
                .build()

            // The manifest offers exactly two types; anything else is a face asking
            // for something we never advertised.
            else -> null
        }

    private fun glanceCopy() = dayGlanceCopy(this)

    private fun text(value: String) = PlainComplicationText.Builder(value).build()

    // No FLAG_ACTIVITY_NEW_TASK: a PendingIntent already launches into its own task,
    // and forcing one is what makes a Wear app show up wrong in recents.
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {

        const val TAG = "DayComplication"

        /** A mid-session day — an editor preview should look like a real Tuesday. */
        val PREVIEW = DayGlance(
            hasProgram = true,
            dayLetter = "A",
            dayTitle = "Lower",
            accentIndex = 0,
            exerciseCount = 3,
            doneSets = 12,
            totalSets = 21,
        )
    }
}
