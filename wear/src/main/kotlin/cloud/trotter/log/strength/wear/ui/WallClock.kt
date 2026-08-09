package cloud.trotter.log.strength.wear.ui

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One formatter for the interactive pill and ambient face. */
internal fun wallClockTimeText(): String =
    LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** A boundary instant waits a full minute; every other instant waits only its remainder. */
internal fun millisUntilNextMinute(nowEpochMillis: Long): Long =
    60_000L - Math.floorMod(nowEpochMillis, 60_000L)
