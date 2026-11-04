package cloud.trotter.log.strength.ui.day.receipt

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.records.PrKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.accentEmphasis
import cloud.trotter.log.strength.ui.theme.accentLabelContainer

/**
 * A list row that opens to show its sets. Material 3 (1.4) has no expandable list item, so this
 * is [ListItem] for the closed row plus [AnimatedVisibility] for the open part, with
 * expanded/collapsed state in semantics. Migrate to material3 1.5's interactive list items once
 * 1.5 is stable.
 */
@Composable
internal fun ExpandableReceiptRow(
    rowKey: String,
    name: String,
    spoken: String,
    expandable: Boolean,
    dayIndex: Int,
    recordKind: PrKind?,
    gutter: String? = null,
    values: @Composable ColumnScope.() -> Unit,
    children: @Composable () -> Unit,
) {
    var open by rememberSaveable(rowKey) { mutableStateOf(false) }
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val labelOnOwnLine = LocalDensity.current.fontScale >= 2f
    val rotation by animateFloatAsState(if (open) 180f else 0f,
        tween(if (reducedMotion) 0 else 200, easing = FastOutSlowInEasing), label = "receiptChevron")
    val state = stringResource(if (open) R.string.log_expanded_state else R.string.log_collapsed_state)
    val action = stringResource(if (open) R.string.log_collapse_label else R.string.log_expand_label)
    Column {
        ListItem(
            headlineContent = {
                Row {
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (expandable) Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(rotation))
                }
            },
            leadingContent = gutter?.let { { Text(it, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp)) } },
            supportingContent = {
                if (labelOnOwnLine) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = values)
                        if (recordKind != null) PrLabel(recordKind, dayIndex)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = values)
                        if (recordKind != null) PrLabel(recordKind, dayIndex)
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0f)),
            modifier = Modifier.then(if (expandable) Modifier.clickable(onClickLabel = action) { open = !open } else Modifier)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    if (expandable) {
                        role = Role.Button
                        stateDescription = state
                        onClick(label = action) { open = !open; true }
                    }
                },
        )
        if (reducedMotion) {
            if (open) ReceiptChildren(children)
        } else {
            AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                ReceiptChildren(children)
            }
        }
    }
}

@Composable
private fun ReceiptChildren(content: @Composable () -> Unit) {
    Column(Modifier.padding(start = 24.dp, end = 16.dp, bottom = 8.dp).indentRule().padding(start = 16.dp)) { content() }
}

@Composable
internal fun SkippedReceiptRow(name: String, gutter: String? = null) {
    val spoken = stringResource(R.string.receipt_say_skipped, name)
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = gutter?.let { { Text(it, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp)) } },
        trailingContent = { Text(stringResource(R.string.day_skipped), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0f)),
        modifier = Modifier.heightIn(min = 56.dp).clearAndSetSemantics { contentDescription = spoken },
    )
}

@Composable
internal fun SupersetGroup(rounds: Int, content: @Composable ColumnScope.() -> Unit) {
    Text(pluralStringResource(R.plurals.receipt_superset_rounds, rounds, rounds),
        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp))
    Column(Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp).indentRule(), content = content)
}

/**
 * The record label on a receipt row: a non-interactive tonal [Surface] with words. Material 3's
 * Badge holds counts and its chips are interactive; M3 has no non-interactive labeled tag. The
 * one exception to DESIGN.md's "no badges".
 */
@Composable
internal fun PrLabel(kind: PrKind, dayIndex: Int) {
    Surface(color = accentLabelContainer(dayIndex), contentColor = accentEmphasis(dayIndex),
        shape = RoundedCornerShape(4.dp), modifier = Modifier.clearAndSetSemantics {}) {
        Text(prLabel(kind), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), softWrap = false)
    }
}

/**
 * Placeholder bar for a value that is being written. Material 3 has no skeleton or placeholder
 * component. The receipt already knows the exercise names, so a bar where the figures will be
 * keeps the layout from jumping when they land. #447 decides whether other screens adopt it.
 */
@Composable
internal fun GhostBar(width: Dp) {
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val alpha = if (reducedMotion) 1f else {
        val transition = rememberInfiniteTransition(label = "receiptGhost")
        val pulse by transition.animateFloat(1f, 0.55f,
            infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "receiptGhostAlpha")
        pulse
    }
    Box(Modifier.height(22.dp).width(width).clip(RoundedCornerShape(4.dp))
        .graphicsLayer { this.alpha = alpha }.background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag("ghost"))
}

/**
 * Placeholder bar for a value that is being written. Material 3 has no skeleton or placeholder
 * component. The receipt already knows the exercise names, so a bar where the figures will be
 * keeps the layout from jumping when they land. #447 decides whether other screens adopt it.
 */
@Composable
internal fun GhostRow(name: String, index: Int, gutter: String? = null) {
    val widths = listOf(150, 140, 110, 120, 130, 90)
    ListItem(headlineContent = { Text(name, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = gutter?.let { { Text(it, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp)) } },
        supportingContent = { GhostBar(widths[index % widths.size].dp) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0f)))
}

@Composable
internal fun StrengthReceiptRow(row: ReceiptRow, unit: WeightUnit, dayIndex: Int, rowKey: String, roll: () -> Float, gutter: String? = null) {
    if (row.shape == ReceiptRow.Shape.SKIPPED) {
        SkippedReceiptRow(row.name, gutter)
        return
    }
    ExpandableReceiptRow(rowKey, row.name, spokenRow(row, unit), row.shape == ReceiptRow.Shape.STRAIGHT,
        dayIndex, row.record?.kind, gutter, values = {
            row.lines.forEach { line ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (row.shape) {
                        ReceiptRow.Shape.TOP_SET -> ValueLabel(stringResource(R.string.receipt_top_set), Modifier.alignByBaseline())
                        ReceiptRow.Shape.HEAVIEST_NO_TOP -> ValueLabel(stringResource(R.string.receipt_heaviest), Modifier.alignByBaseline())
                        else -> Unit
                    }
                    val recordIndex = row.record?.setIndex
                    val containsRecord = recordIndex != null && recordIndex in line.setIndices
                    val emphasis = accentEmphasis(dayIndex)
                    val text = buildAnnotatedString {
                        if (row.tracking == TrackingType.WEIGHTED) append(visibleWeight(requireNotNull(line.weightLb), unit) + " × ")
                        line.setIndices.forEachIndexed { position, index ->
                            if (position > 0) append(", ")
                            val value = if (row.tracking == TrackingType.WEIGHTED) row.sets[index].reps.toString()
                                else visibleSet(row.sets[index], row.tracking, unit)
                            if (index == recordIndex && row.shape == ReceiptRow.Shape.STRAIGHT) {
                                withStyle(SpanStyle(color = emphasis, textDecoration = TextDecoration.Underline)) { append(value) }
                            } else append(value)
                        }
                    }
                    Text(text, style = MaterialTheme.typography.headlineSmall,
                        color = if (containsRecord && row.shape != ReceiptRow.Shape.STRAIGHT) emphasis else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.alignByBaseline().then(if (containsRecord) Modifier.graphicsLayer {
                            translationY = roll() * 0.6f * size.height; alpha = 1 - roll()
                        } else Modifier))
                    if (row.shape == ReceiptRow.Shape.TOP_SET && row.record != null) {
                        OldBest(visibleSet(row.record.previous, row.tracking, unit), Modifier.alignByBaseline())
                    }
                }
            }
            if (row.lighterSets > 0) ValueLabel(pluralStringResource(R.plurals.receipt_lighter_sets, row.lighterSets, row.lighterSets))
            if (row.shape == ReceiptRow.Shape.HEAVIEST_NO_TOP) ValueLabel(stringResource(R.string.receipt_top_set_not_logged))
        }, children = {
            row.sets.forEachIndexed { index, set ->
                val mark = row.record?.takeIf { it.setIndex == index }
                ReceiptSetLine(index, visibleSet(set, set.tracking(), unit), spokenSetLine(row, index, unit), dayIndex,
                    mark?.let { stringResource(R.string.receipt_set_pr_note, visibleSet(it.previous, row.tracking, unit)) })
            }
            row.planned?.let { ReceiptAfter(visiblePlan(it, row.tracking, unit), spokenPlan(it, row.tracking, unit)) }
        })
}

@Composable
private fun ValueLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun OldBest(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textDecoration = TextDecoration.LineThrough, modifier = modifier)
}

@Composable
private fun ReceiptSetLine(index: Int, value: String, spoken: String, dayIndex: Int, note: String?) {
    FlowRow(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(top = 4.dp).clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text((index + 1).toString(), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp).alignByBaseline())
        Text(value, style = MaterialTheme.typography.titleMedium,
            color = if (note == null) MaterialTheme.colorScheme.onSurface else accentEmphasis(dayIndex), modifier = Modifier.alignByBaseline())
        if (note != null) Text(note, style = MaterialTheme.typography.labelMedium, color = accentEmphasis(dayIndex), modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun ReceiptAfter(text: String, spoken: String) {
    Box(Modifier.heightIn(min = 30.dp).clearAndSetSemantics { contentDescription = spoken }) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CardioReceiptRowItem(row: CardioReceiptRow, dayIndex: Int, rowKey: String, roll: () -> Float) {
    val name = cardioRowName(row)
    if (row.role != CardioRole.WORK && row.intervals.all { it.status == CardioSetStatus.SKIPPED }) {
        SkippedReceiptRow(name)
        return
    }
    ExpandableReceiptRow(rowKey, name, spokenCardioRow(row), row.role == CardioRole.WORK && row.count > 1,
        dayIndex, row.record?.kind, values = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (row.role == CardioRole.WORK && bestInterval(row) != null) ValueLabel(cardioValueLabel(row), Modifier.alignByBaseline())
                val missing = row.role == CardioRole.WORK && bestInterval(row) == null
                Text(cardioValue(row), style = if (missing) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.headlineSmall,
                    color = if (missing) MaterialTheme.colorScheme.onSurfaceVariant
                        else if (row.record != null) accentEmphasis(dayIndex) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alignByBaseline().then(if (row.record != null) Modifier.graphicsLayer {
                        translationY = roll() * 0.6f * size.height; alpha = 1 - roll()
                    } else Modifier))
                row.record?.let { OldBest(cardioPrevious(it), Modifier.alignByBaseline()) }
            }
        }, children = {
            row.intervals.forEachIndexed { index, interval ->
                ReceiptSetLine(index, cardioIntervalValue(row, interval), spokenInterval(row, index), dayIndex,
                    row.record?.takeIf { it.intervalIndex == index }?.let { stringResource(R.string.receipt_set_pr_note, cardioPrevious(it)) })
            }
            if (row.recoverySeconds.isNotEmpty()) ReceiptAfter(recoveryText(row.recoverySeconds), recoveryText(row.recoverySeconds, spoken = true))
        })
}

/** The rule down the left of indented content, drawn rather than laid out: a VerticalDivider
 *  needs an intrinsic-height parent, and the rows inside report unreliable intrinsics. */
@Composable
private fun Modifier.indentRule(): Modifier {
    val color = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        val x = 0.5.dp.toPx()
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
    }
}
