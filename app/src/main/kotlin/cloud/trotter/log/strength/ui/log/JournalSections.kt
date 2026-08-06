package cloud.trotter.log.strength.ui.log

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.Done
import cloud.trotter.log.strength.ui.theme.StepperRepsValue
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.TabLetter
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.accentBright
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent

// The journal's three sections (docs/briefs/journal.md §1). These render the
// prebuilt models JournalBuilder produces and derive nothing themselves; chart
// geometry comes entirely from the measured Canvas size.
//
// §0's color rules are binding here: one series per chart and no legends; line
// and marker marks take the day's *bright* accent (the raw fill accent is
// unreadable on near-black); a colored calendar cell always carries its day
// letter; values, axis labels and captions wear text tokens, never the accent.

private val PLOT_HEIGHT = 96.dp
private val VOLUME_HEIGHT = 76.dp
private val LABEL_GAP = 8.dp

// --- TRAJECTORY --------------------------------------------------------------

@Composable
internal fun TrajectoryCardView(card: TrajectoryCard) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.exerciseName,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (!card.plotted) {
                Text(card.latestLabel, color = TextPrimary, style = StepperRepsValue)
            }
        }
        Spacer(Modifier.size(2.dp))
        if (card.plotted) {
            TrajectoryPlot(card, Modifier.fillMaxWidth().height(PLOT_HEIGHT))
        } else {
            // Fewer than two sessions is not a trajectory — the number and the
            // target say everything a line would (§1.1).
            Text(
                card.goalLabel,
                color = if (card.goalMet) Done else TextFaint,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(card.caption, color = TextFaint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TrajectoryPlot(card: TrajectoryCard, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val lineColor = accentBright(card.dayIndex)
    val goalColor = if (card.goalMet) Done else TextFaint
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = TextFaint)
    val goalStyle = MaterialTheme.typography.labelSmall.copy(color = goalColor)
    val valueStyle = StepperRepsValue.copy(color = TextPrimary)
    val description = "${card.exerciseName} top set trajectory, latest ${card.latestLabel}, ${card.goalLabel}"

    Canvas(modifier.semantics { contentDescription = description }) {
        val gridLabels = card.gridlines.map { measurer.measure(it.label, axisStyle) }
        val goalLabel = measurer.measure(card.goalLabel, goalStyle)
        val valueLabel = measurer.measure(card.latestLabel, valueStyle)

        val left = (gridLabels.maxOfOrNull { it.size.width.toFloat() } ?: 0f) + LABEL_GAP.toPx()
        val right = size.width - goalLabel.size.width - LABEL_GAP.toPx()
        val top = valueLabel.size.height.toFloat()
        val bottom = size.height - LABEL_GAP.toPx()
        if (right <= left || bottom <= top) return@Canvas

        val span = (card.axisMax - card.axisMin).takeIf { it > 0f } ?: 1f
        fun y(value: Float) = bottom - (value - card.axisMin) / span * (bottom - top)

        for ((i, grid) in card.gridlines.withIndex()) {
            val gy = y(grid.value)
            drawLine(TextFaint.copy(alpha = 0.28f), Offset(left, gy), Offset(right, gy), strokeWidth = 1.dp.toPx())
            drawText(gridLabels[i], topLeft = Offset(0f, gy - gridLabels[i].size.height / 2f))
        }

        val goalY = y(card.goalValue)
        drawLine(
            color = goalColor,
            start = Offset(left, goalY),
            end = Offset(right, goalY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        )
        drawText(
            goalLabel,
            topLeft = Offset(
                x = size.width - goalLabel.size.width,
                y = (goalY - goalLabel.size.height / 2f).coerceIn(0f, size.height - goalLabel.size.height),
            ),
        )

        val step = (right - left) / (card.points.size - 1)
        val xs = card.points.indices.map { left + it * step }
        val ys = card.points.map { y(it.value) }

        // Step-after: the weight holds until the session that changes it.
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

        val lastY = ys.last()
        val labelAbove = lastY - valueLabel.size.height - LABEL_GAP.toPx() >= 0f
        drawText(
            valueLabel,
            topLeft = Offset(
                x = right - valueLabel.size.width,
                y = if (labelAbove) lastY - valueLabel.size.height - 2.dp.toPx() else lastY + 2.dp.toPx(),
            ),
        )
    }
}

// --- VOLUME ------------------------------------------------------------------

@Composable
internal fun VolumeCardView(chart: VolumeChart) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = TextFaint)
    // The ember accent is Day A's hex (spec §8.5) used here as this chart's one
    // hue — a filled bar, not a hairline, so the base accent reads fine.
    val barColor = dayAccent(0)
    val trainedWeeks = chart.bars.count { it.trained }

    AppCard {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(VOLUME_HEIGHT)
                .semantics { contentDescription = "Weekly tonnage, $trainedWeeks of ${chart.bars.size} weeks trained" },
        ) {
            drawVolumeBars(chart, barColor, measurer, labelStyle)
        }
        Spacer(Modifier.size(6.dp))
        Text(
            "LAST ${chart.bars.size} WEEKS",
            color = TextFaint,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun DrawScope.drawVolumeBars(
    chart: VolumeChart,
    barColor: Color,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val count = chart.bars.size
    val gap = 2.dp.toPx()
    val maxBar = 12.dp.toPx()
    val barWidth = minOf(maxBar, (size.width - gap * (count - 1)) / count)
    if (barWidth <= 0f) return
    val startX = (size.width - (barWidth * count + gap * (count - 1))) / 2f

    val baseline = size.height
    val labelHeight = measurer.measure("0", labelStyle).size.height.toFloat()
    val plotTop = labelHeight + LABEL_GAP.toPx()
    val plotHeight = baseline - plotTop
    if (plotHeight <= 0f) return

    drawLine(Border, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())

    chart.bars.forEachIndexed { i, bar ->
        val x = startX + i * (barWidth + gap)
        if (bar.trained) {
            val height = (bar.fraction * plotHeight).coerceAtLeast(2.dp.toPx())
            drawPath(roundTopBar(x, x + barWidth, baseline - height, baseline, 4.dp.toPx()), barColor)
        }
        val label = bar.label ?: return@forEachIndexed
        val measured = measurer.measure(label, labelStyle)
        drawText(
            measured,
            topLeft = Offset(
                x = (x + barWidth / 2f - measured.size.width / 2f)
                    .coerceIn(0f, size.width - measured.size.width),
                y = 0f,
            ),
        )
    }
}

/** A bar with its top corners rounded and its base flat on the axis. */
private fun Density.roundTopBar(left: Float, right: Float, top: Float, bottom: Float, radius: Float): Path {
    val r = minOf(radius, (right - left) / 2f, bottom - top)
    return Path().apply {
        moveTo(left, bottom)
        lineTo(left, top + r)
        quadraticTo(left, top, left + r, top)
        lineTo(right - r, top)
        quadraticTo(right, top, right, top + r)
        lineTo(right, bottom)
        close()
    }
}

// --- CALENDAR ----------------------------------------------------------------

private val WEEKDAY_INITIALS = listOf("M", "T", "W", "T", "F", "S", "S")

@Composable
internal fun CalendarCardView(
    month: CalendarMonth,
    onPage: (Int) -> Unit,
    onSelectSession: (Long) -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                month.title,
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            MonthChevron("‹", "Previous month", month.canPageBack) { onPage(-1) }
            Spacer(Modifier.size(6.dp))
            MonthChevron("›", "Next month", month.canPageForward) { onPage(1) }
        }
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth()) {
            WEEKDAY_INITIALS.forEach { initial ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(initial, color = TextFaint, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        val cells: List<CalendarDay?> = List(month.leadingBlanks) { null } + month.days
        cells.chunked(WEEKDAY_INITIALS.size).forEach { week ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                week.forEach { day ->
                    Box(Modifier.weight(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (day != null) CalendarCell(day, onSelectSession)
                    }
                }
                repeat(WEEKDAY_INITIALS.size - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MonthChevron(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(Surface2, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (enabled) TextSecondary else TextFaint.copy(alpha = 0.4f),
            style = TabLetter,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** A trained day is an accent pill carrying its day *letter* — the letter is the
 *  identity, the accent only the flavor (§0: color is never the only carrier). */
@Composable
private fun CalendarCell(day: CalendarDay, onSelectSession: (Long) -> Unit) {
    val trained = day.dayLetter != null
    val todayRing = if (day.isToday) Modifier.border(1.dp, TextSecondary, RoundedCornerShape(8.dp)) else Modifier
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .then(if (trained) Modifier.background(dayAccent(day.dayIndex)) else Modifier)
            .then(todayRing)
            .then(
                if (day.sessionId != null) {
                    Modifier.clickable(onClickLabel = "Show session") { onSelectSession(day.sessionId) }
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = if (trained) "${day.dayOfMonth}, day ${day.dayLetter}" else "${day.dayOfMonth}"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (trained) day.dayLetter.orEmpty() else day.dayOfMonth.toString(),
            color = if (trained) onDayAccent(day.dayIndex) else TextFaint,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clearAndSetSemantics {},
        )
        if (day.moreSessions) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .size(3.dp)
                    .background(onDayAccent(day.dayIndex), RoundedCornerShape(50)),
            )
        }
    }
}

// --- shared ------------------------------------------------------------------

/** The screen's quiet caps section rule — one composable so TRAJECTORY, VOLUME,
 *  CALENDAR and FROM OTHER APPS are literally the same header. */
@Composable
internal fun LogSectionHeader(title: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, color = TextFaint, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.size(2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}
