package cloud.trotter.log.strength.ui.today

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TodayScreenBuilder {
    /** One history fact, including cardio, with no progress or advice sentence. */
    fun coachLine(lastCompletedAt: Instant?, zone: ZoneId): String? = lastCompletedAt?.let {
        "Last trained ${it.atZone(zone).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))}"
    }
}
