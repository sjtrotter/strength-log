package cloud.trotter.log.strength.wear.glance

import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders.ARC_ANCHOR_START
import androidx.wear.protolayout.LayoutElementBuilders.ARC_DIRECTION_CLOCKWISE
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
// Medium would suit the caps label better, but it is still ProtoLayoutExperimental;
// normal and bold are the two weights a tile can use without an opt-in.
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_NORMAL
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.STROKE_CAP_BUTT
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.TEXT_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import cloud.trotter.log.strength.wear.theme.Background
import cloud.trotter.log.strength.wear.theme.Border
import cloud.trotter.log.strength.wear.theme.Done
import cloud.trotter.log.strength.wear.theme.TextPrimary
import cloud.trotter.log.strength.wear.theme.TextSecondary
import cloud.trotter.log.strength.wear.theme.TextTertiary
import cloud.trotter.log.strength.wear.theme.accentBright

/**
 * The tile as one layout: the dial's outer day ring — green progress on the
 * border-gray track, starting at twelve o'clock — around the day and its set count.
 *
 * No lists, no buttons, one clickable that opens the app. The renderer gives us the
 * system font instead of Barlow Condensed, which is the one piece of the app's face
 * a tile can't carry; the ring vocabulary does the identifying instead.
 *
 * Colors come from the same tokens the dial paints with, converted to ARGB here
 * rather than restated — the hexes stay SSOT in `theme/` and `:domain`.
 */
internal fun dayTileLayout(glance: DayGlance, onClick: ModifiersBuilders.Clickable): LayoutElement {
    val face = Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(onClick)
                .setBackground(
                    ModifiersBuilders.Background.Builder()
                        .setColor(argb(Background.toArgb()))
                        .build(),
                )
                .setSemantics(
                    ModifiersBuilders.Semantics.Builder()
                        .setContentDescription(glance.contentDescription)
                        .build(),
                )
                .build(),
        )
        .addContent(ring(FULL_TURN_DEGREES, Border.toArgb()))
    if (glance.progress > 0f) {
        face.addContent(ring(FULL_TURN_DEGREES * glance.progress, Done.toArgb()))
    }
    return face.addContent(center(glance)).build()
}

/** One ring of the dial: an arc that starts at twelve and runs clockwise. */
private fun ring(sweepDegrees: Float, color: Int): LayoutElement = Arc.Builder()
    .setAnchorAngle(degrees(0f))
    .setAnchorType(ARC_ANCHOR_START)
    .setArcDirection(ARC_DIRECTION_CLOCKWISE)
    .addContent(
        ArcLine.Builder()
            .setLength(degrees(sweepDegrees))
            .setThickness(dp(RING_THICKNESS_DP))
            .setColor(argb(color))
            .setStrokeCap(STROKE_CAP_BUTT)
            .build(),
    )
    .build()

/** The day, then the set count — the phone widget's two lines, stacked. */
private fun center(glance: DayGlance): LayoutElement = Column.Builder()
    .setWidth(expand())
    .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
    .setModifiers(
        ModifiersBuilders.Modifiers.Builder()
            .setPadding(
                ModifiersBuilders.Padding.Builder()
                    .setStart(dp(CENTER_INSET_DP))
                    .setEnd(dp(CENTER_INSET_DP))
                    .build(),
            )
            .build(),
    )
    .addContent(
        line(
            text = glance.titleLine,
            sizeSp = TITLE_SIZE_SP,
            weight = FONT_WEIGHT_NORMAL,
            color = if (glance.hasProgram) accentBright(glance.accentIndex).toArgb() else TextSecondary.toArgb(),
        ),
    )
    .addContent(Spacer.Builder().setHeight(dp(LINE_GAP_DP)).build())
    .addContent(
        line(
            text = glance.setLine,
            sizeSp = if (glance.hasProgram) HERO_SIZE_SP else TITLE_SIZE_SP,
            weight = if (glance.hasProgram) FONT_WEIGHT_BOLD else FONT_WEIGHT_NORMAL,
            color = when {
                !glance.hasProgram -> TextTertiary.toArgb()
                glance.done -> Done.toArgb()
                else -> TextPrimary.toArgb()
            },
        ),
    )
    .build()

/** Two lines at most, then an ellipsis: an unusually long emphasis must not clip the ring. */
private fun line(text: String, sizeSp: Float, weight: Int, color: Int): LayoutElement = Text.Builder()
    .setText(text)
    .setMaxLines(2)
    .setMultilineAlignment(TEXT_ALIGN_CENTER)
    .setOverflow(TEXT_OVERFLOW_ELLIPSIZE)
    .setFontStyle(
        FontStyle.Builder()
            .setSize(sp(sizeSp))
            .setWeight(weight)
            .setColor(argb(color))
            .build(),
    )
    .build()

private const val FULL_TURN_DEGREES = 360f

/**
 * Thicker than the dial's own day ring (5px of a 384px face). There it sits outside
 * a 14px exercise ring; here it is the only ring on the tile and has to carry the
 * whole reading on its own.
 */
private const val RING_THICKNESS_DP = 6f

private const val CENTER_INSET_DP = 28f
private const val LINE_GAP_DP = 8f
private const val TITLE_SIZE_SP = 13f
private const val HERO_SIZE_SP = 20f
