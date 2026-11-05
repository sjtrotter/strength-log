package cloud.trotter.log.strength.ui.log

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.components.pressable
import cloud.trotter.log.strength.ui.theme.Done
import cloud.trotter.log.strength.ui.theme.accentEmphasis
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
private fun shortDateFormatter(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
}

@Composable
internal fun TrajectoryCardView(card: TrajectoryCard) {
    val dateFormat = shortDateFormatter()
    val unit = card.unit.name.lowercase(Locale.ROOT)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.log_top_set_heading, card.exerciseName),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        if (!card.plotted) {
            Text(
                stringResource(R.string.log_top_set_insufficient),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (card.points.isNotEmpty()) {
            Text(
                stringResource(R.string.log_top_set_latest, card.latestLabel, unit),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (card.goalMet) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.log_top_set_goal_met), tint = Done)
            Text(
                stringResource(R.string.log_top_set_goal, card.goalLabel, unit),
                color = if (card.goalMet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (card.plotted) {
            val first = card.points.first()
            val last = card.points.last()
            val description = stringResource(
                R.string.log_top_set_series_description,
                card.exerciseName, dateFormat.format(first.date), WeightStepper.format(first.value.toDouble()),
                dateFormat.format(last.date), WeightStepper.format(last.value.toDouble()), unit, card.goalLabel,
            )
            TrajectoryPlot(card, Modifier.fillMaxWidth().height(128.dp).semantics { contentDescription = description })
            Text(
                stringResource(R.string.log_top_set_date_range, dateFormat.format(first.date), dateFormat.format(last.date)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Material 3 has no trajectory plot. Canvas contains only the series and goal marks. */
@Composable
private fun TrajectoryPlot(card: TrajectoryCard, modifier: Modifier) {
    val lineColor = accentEmphasis(card.dayIndex)
    val goalColor = if (card.goalMet) Done else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val inset = 4.dp.toPx()
        val left = inset
        val right = size.width - inset
        val top = inset
        val bottom = size.height - inset
        val span = (card.axisMax - card.axisMin).takeIf { it > 0f } ?: 1f
        fun y(value: Float) = bottom - (value - card.axisMin) / span * (bottom - top)
        val goalY = y(card.goalValue)
        drawLine(
            color = goalColor,
            start = Offset(left, goalY),
            end = Offset(right, goalY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        )
        val step = (right - left) / (card.points.size - 1)
        val xs = card.points.indices.map { left + it * step }
        val ys = card.points.map { y(it.value) }
        val path = Path().apply {
            moveTo(xs.first(), ys.first())
            for (i in 1 until xs.size) {
                lineTo(xs[i], ys[i - 1])
                lineTo(xs[i], ys[i])
            }
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        card.points.forEachIndexed { i, point ->
            if (point.newHigh) drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(xs[i], ys[i]))
        }
    }
}

@Composable
internal fun VolumeWeekRow(week: VolumeWeek) {
    val dateFormat = shortDateFormatter()
    val numberFormat = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply {
        maximumFractionDigits = 2
    }
    val total = week.total
    val trailing = when {
        total == null -> stringResource(R.string.log_week_volume_unavailable)
        !week.trained -> stringResource(R.string.log_week_no_sessions)
        else -> stringResource(R.string.log_week_volume_total, numberFormat.format(total), week.unit.name.lowercase(Locale.ROOT))
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.log_week_of, dateFormat.format(week.startDate))) },
        trailingContent = { Text(trailing, style = MaterialTheme.typography.bodyMedium) },
    )
}

private val WEEKDAY_INITIALS = listOf(
    R.string.log_weekday_monday,
    R.string.log_weekday_tuesday,
    R.string.log_weekday_wednesday,
    R.string.log_weekday_thursday,
    R.string.log_weekday_friday,
    R.string.log_weekday_saturday,
    R.string.log_weekday_sunday,
)

/** Material 3 has no month grid. Seven 48 dp cells use 12 dp screen insets at 360 dp. */
@Composable
internal fun CalendarCardView(
    month: CalendarMonth,
    onPage: (Int) -> Unit,
    onSelectSession: (Long) -> Unit,
) {
    Column {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                month.title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onPage(-1) }, enabled = month.canPageBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.log_calendar_previous_month))
            }
            IconButton(onClick = { onPage(1) }, enabled = month.canPageForward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.log_calendar_next_month))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            WEEKDAY_INITIALS.forEach { initial ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(initial), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        val cells: List<CalendarDay?> = List(month.leadingBlanks) { null } + month.days
        cells.chunked(WEEKDAY_INITIALS.size).forEach { week ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                week.forEach { day ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (day != null) CalendarCell(day, onSelectSession)
                    }
                }
                repeat(WEEKDAY_INITIALS.size - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Material 3 has no trained-date cell with both a date and a rotation letter. */
@Composable
private fun CalendarCell(day: CalendarDay, onSelectSession: (Long) -> Unit) {
    val trained = day.dayLetter != null
    val shape = RoundedCornerShape(8.dp)
    val showSessionLabel = stringResource(R.string.log_calendar_show_session)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .then(if (trained) Modifier.background(dayAccent(day.dayIndex)) else Modifier)
            .then(if (day.isToday) Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, shape) else Modifier)
            .then(
                if (day.sessionId != null) {
                    Modifier.pressable(onClickLabel = showSessionLabel, shape = shape) { onSelectSession(day.sessionId) }
                } else Modifier,
            )
            .semantics { contentDescription = day.label }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            day.dayOfMonth.toString(),
            color = if (trained) onDayAccent(day.dayIndex) else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clearAndSetSemantics {},
        )
        if (trained) {
            Text(
                day.dayLetter.orEmpty(),
                color = onDayAccent(day.dayIndex),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        if (day.moreSessions) {
            Box(Modifier.size(3.dp).background(onDayAccent(day.dayIndex), RoundedCornerShape(50)))
        }
    }
}

@Composable
internal fun LogSectionHeader(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
