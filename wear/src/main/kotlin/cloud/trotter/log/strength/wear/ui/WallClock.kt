package cloud.trotter.log.strength.wear.ui

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Full localized short time for the ambient face, where the available width permits it. */
internal fun wallClockTimeText(): String =
    LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/**
 * Compact interactive-pill time. Meridiem is deliberately omitted at glance distance;
 * [is24Hour] still honors the device preference by selecting a 12- or 24-hour hour value.
 */
internal fun timePillTimeText(time: LocalTime, is24Hour: Boolean): String =
    time.format(DateTimeFormatter.ofPattern(if (is24Hour) "H:mm" else "h:mm"))

/** A boundary instant waits a full minute; every other instant waits only its remainder. */
internal fun millisUntilNextMinute(nowEpochMillis: Long): Long =
    60_000L - Math.floorMod(nowEpochMillis, 60_000L)
