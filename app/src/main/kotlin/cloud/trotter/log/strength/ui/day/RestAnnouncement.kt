package cloud.trotter.log.strength.ui.day

import android.content.res.Resources
import cloud.trotter.log.strength.R

internal fun formatRestAnnouncement(resources: Resources, seconds: Int): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    val duration = buildList {
        if (minutes > 0) add(resources.getQuantityString(R.plurals.workout_rest_minutes, minutes, minutes))
        if (remainder > 0 || minutes == 0) {
            add(resources.getQuantityString(R.plurals.workout_rest_seconds, remainder, remainder))
        }
    }.joinToString(" ")
    return resources.getString(R.string.workout_rest_duration, duration)
}
