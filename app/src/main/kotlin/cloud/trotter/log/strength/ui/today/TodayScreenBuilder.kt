package cloud.trotter.log.strength.ui.today

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TodayScreenBuilder {
    /** Date for the one history fact, including cardio; the screen supplies the label. */
    fun coachLine(lastCompletedAt: Instant?, zone: ZoneId): String? = lastCompletedAt?.let {
        it.atZone(zone).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    }
}
