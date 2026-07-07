package io.github.sjtrotter.strengthlog.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.RemoveGlyph
import io.github.sjtrotter.strengthlog.ui.theme.SetKindLabel
import io.github.sjtrotter.strengthlog.ui.theme.StepperRepsValue
import io.github.sjtrotter.strengthlog.ui.theme.TextFaint
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary
import io.github.sjtrotter.strengthlog.ui.theme.accentSoft
import io.github.sjtrotter.strengthlog.ui.theme.dayAccent
import kotlinx.coroutines.delay

private val PrimaryRowMinHeight = 52.dp
private val SubRowMinHeight = 46.dp
private val PrimaryKindLabelWidth = 30.dp
private val SubRowKindLabelWidth = 16.dp
private val TopRowBarWidth = 3.dp
private val TopRowCorner = 8.dp
private const val CASCADE_FLASH_MS = 650
private const val CASCADE_STAGGER_MS = 45L
private const val TICK_FADE_MS = 200

/**
 * One logged set (design-pass restyle: `.srow` in
 * docs/design-handoff/day_screen_reference.html) — the app's most-repeated
 * element, so it gets its own component. The day screen consumes this; the
 * watch does not, so this stays UI-layer only (no `ui/day` model imports —
 * every value is a primitive/callback the caller already has on hand).
 *
 * Layout: `[kind label][weight capsule][reps capsule][spacer][tick][remove ×]`,
 * 8dp gaps, min height 52dp (46dp for a superset sub-row, [isSubRow]).
 *
 * - [isTop] paints the [accentSoft] fill (rounded, 3dp [accent] left bar) across
 *   the row's own bounds and colors the kind label [accent]. The 10dp bleed into
 *   the card gutter is the caller's job: `DayScreen` widens the TOP row's
 *   container past the card content edge (a clipping ancestor would otherwise
 *   cut a fill drawn outside these bounds), so this only draws within bounds.
 *   Callers pass both colors (rather than a day index) so the A/C-vs-B/D 12%/14%
 *   soft-fill split (see `accentSoft(dayIndex)` in Color.kt) stays computed
 *   in exactly one place.
 * - [ticked] fades the steppers to 55% opacity (the row itself is still
 *   editable — spec allows correcting a logged set after ticking it).
 * - [isSubRow] renders the superset partner: no tick/remove, a `↳` marker,
 *   a dashed top hairline, and a narrower kind-label column. The caller is
 *   responsible for the 30dp left indent (see `DayScreen`'s partner-row
 *   wrapper), since indentation is a card-layout concern, not this row's.
 *
 * Cascade flash: when [weight] changes for a reason other than this row's own
 * stepper (i.e. a TOP-weight edit elsewhere recalculated this row), the row
 * flashes an accent-soft background and its value numeral flashes [accent],
 * both fading out over 650ms. Detected by snapshotting the previous [weight]
 * in this composable — never from new ViewModel state (motion rule). [isTop]
 * rows never flash themselves, matching the reference. [cascadeOrdinal]
 * staggers the flash 45ms per row for a group of rows that update together.
 */
@Composable
fun SetRow(
    kindLabel: String,
    accent: Color,
    accentSoft: Color,
    weight: Double,
    onWeightChange: (Double) -> Unit,
    weightStep: (Double) -> Double,
    weightFormat: (Double) -> String,
    reps: Int,
    onRepsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isTop: Boolean = false,
    isSubRow: Boolean = false,
    ticked: Boolean = false,
    weightRound: (Double) -> Double = { it },
    onToggleDone: (Boolean) -> Unit = {},
    onRemove: () -> Unit = {},
    cascadeOrdinal: Int = 0,
) {
    var previousWeight by remember { mutableDoubleStateOf(weight) }
    var selfEdited by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }

    LaunchedEffect(weight) {
        if (weight != previousWeight) {
            if (!selfEdited && !isTop) {
                delay(cascadeOrdinal * CASCADE_STAGGER_MS)
                flash.snapTo(1f)
                flash.animateTo(0f, tween(CASCADE_FLASH_MS, easing = LinearEasing))
            }
            previousWeight = weight
            selfEdited = false
        }
    }

    val fadeAlpha by animateFloatAsState(
        targetValue = if (ticked) 0.55f else 1f,
        animationSpec = tween(TICK_FADE_MS),
        label = "setRowTickedFade",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (isSubRow) SubRowMinHeight else PrimaryRowMinHeight)
            .then(if (isSubRow) Modifier.dashedTopBorder(Border) else Modifier)
            .drawBehind {
                if (isTop) drawTopRowBleed(accent, accentSoft) else drawCascadeFlash(accentSoft, flash.value)
            }
            // TOP content sits 10dp in (reference `.srow.top` border-box: 3dp bar
            // + 7dp padding), which aligns it with the sibling rows the day screen
            // insets 10dp; those rows sit flush here so only the TOP row can bleed.
            .then(if (isTop) Modifier.padding(start = 10.dp, end = 10.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (isSubRow) "↳" else kindLabel,
            color = if (isTop) accent else if (isSubRow) TextFaint else TextSecondary,
            style = SetKindLabel,
            modifier = Modifier.width(if (isSubRow) SubRowKindLabelWidth else PrimaryKindLabelWidth),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.alpha(fadeAlpha)) {
            Stepper(
                value = weight,
                onValueChange = {
                    selfEdited = true
                    onWeightChange(it)
                },
                step = weightStep,
                format = weightFormat,
                round = weightRound,
                valueColor = lerp(TextPrimary, accent, flash.value),
            )
            Stepper(
                value = reps.toDouble(),
                onValueChange = { onRepsChange(it.toInt()) },
                step = { 1.0 },
                minValue = 1.0,
                format = { it.toInt().toString() },
                valueTextStyle = StepperRepsValue,
                valueMinWidth = 36.dp,
            )
        }

        Spacer(Modifier.weight(1f))

        if (!isSubRow) {
            CheckmarkToggle(checked = ticked, onCheckedChange = onToggleDone)
            RemoveButton(onClick = onRemove)
        }
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(width = 24.dp, height = 48.dp), contentAlignment = Alignment.Center) {
            Text(text = "×", color = TextFaint, style = RemoveGlyph)
        }
    }
}

/** Rounded accent-soft fill + 3dp accent left bar, drawn within the row's own
 *  (caller-widened) bounds — reference `.srow.top` (radius 8, border-left 3). */
private fun DrawScope.drawTopRowBleed(accent: Color, accentSoft: Color) {
    val cornerPx = TopRowCorner.toPx()
    val barPx = TopRowBarWidth.toPx()
    drawRoundRect(color = accentSoft, cornerRadius = CornerRadius(cornerPx))
    // Bar inset vertically by the corner radius so it doesn't poke the rounded edge.
    drawRect(color = accent, topLeft = Offset(0f, cornerPx), size = Size(barPx, size.height - 2 * cornerPx))
}

/** Transient full-width accent-soft flash, read in the draw phase so only redraw (not recomposition) tracks it. */
private fun DrawScope.drawCascadeFlash(accentSoft: Color, progress: Float) {
    if (progress <= 0f) return
    drawRect(color = accentSoft.copy(alpha = accentSoft.alpha * progress))
}

private fun Modifier.dashedTopBorder(color: Color): Modifier = drawBehind {
    val stroke = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    drawLine(
        brush = SolidColor(color),
        start = Offset(0f, 0f),
        end = Offset(size.width, 0f),
        strokeWidth = stroke.width,
        pathEffect = stroke.pathEffect,
    )
}

// --- previews: plain / TOP / ticked / superset sub-row -----------------------

@Preview(showBackground = true, backgroundColor = 0xFF16161A)
@Composable
private fun SetRowPlainPreview() {
    AppTheme {
        var w by remember { mutableDoubleStateOf(210.0) }
        var r by remember { mutableIntStateOf(3) }
        SetRow(
            kindLabel = "R4",
            accent = dayAccent(0),
            accentSoft = accentSoft(0),
            weight = w,
            onWeightChange = { w = it },
            weightStep = { WeightStepper.increment(it, WeightUnit.LB) },
            weightFormat = WeightStepper::format,
            weightRound = { WeightStepper.round(it, WeightUnit.LB) },
            reps = r,
            onRepsChange = { r = it },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF16161A)
@Composable
private fun SetRowTopPreview() {
    AppTheme {
        var w by remember { mutableDoubleStateOf(235.0) }
        var r by remember { mutableIntStateOf(5) }
        SetRow(
            kindLabel = "TOP",
            accent = dayAccent(0),
            accentSoft = accentSoft(0),
            weight = w,
            onWeightChange = { w = it },
            weightStep = { WeightStepper.increment(it, WeightUnit.LB) },
            weightFormat = WeightStepper::format,
            weightRound = { WeightStepper.round(it, WeightUnit.LB) },
            reps = r,
            onRepsChange = { r = it },
            isTop = true,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF16161A)
@Composable
private fun SetRowTickedPreview() {
    AppTheme {
        SetRow(
            kindLabel = "B/O",
            accent = dayAccent(0),
            accentSoft = accentSoft(0),
            weight = 175.0,
            onWeightChange = {},
            weightStep = { WeightStepper.increment(it, WeightUnit.LB) },
            weightFormat = WeightStepper::format,
            weightRound = { WeightStepper.round(it, WeightUnit.LB) },
            reps = 8,
            onRepsChange = {},
            ticked = true,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF16161A)
@Composable
private fun SetRowSupersetSubRowPreview() {
    AppTheme {
        SetRow(
            kindLabel = "",
            accent = dayAccent(0),
            accentSoft = accentSoft(0),
            weight = 50.0,
            onWeightChange = {},
            weightStep = { WeightStepper.increment(it, WeightUnit.LB) },
            weightFormat = WeightStepper::format,
            weightRound = { WeightStepper.round(it, WeightUnit.LB) },
            reps = 15,
            onRepsChange = {},
            isSubRow = true,
        )
    }
}
