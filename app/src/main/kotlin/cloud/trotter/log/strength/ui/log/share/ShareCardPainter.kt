package cloud.trotter.log.strength.ui.log.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.theme.DarkBorder
import cloud.trotter.log.strength.ui.theme.DarkBackground
import cloud.trotter.log.strength.ui.theme.DarkTextFaint
import cloud.trotter.log.strength.ui.theme.DarkTextPrimary
import cloud.trotter.log.strength.ui.theme.DarkTextSecondary
import cloud.trotter.log.strength.ui.theme.accentBright

/**
 * Deterministic `android.graphics` rendering of a [ShareCardContent] onto a
 * 1080×1350 (4:5) canvas (docs/briefs/session-share.md §2) — no off-screen
 * Compose. Dumb by design: every string, line count and color decision was
 * already made by [ShareCardContentBuilder]; this only lays glyphs out top
 * to bottom. Fonts load through [ResourcesCompat] and colors come straight
 * from the app's Compose tokens ([Background], [Border], the text/accent
 * scale) via `.toArgb()` — read, never a second hex table.
 */
object ShareCardPainter {

    internal data class ColorInput(
        val background: Int,
        val accent: Int,
    )

    const val WIDTH = 1080
    const val HEIGHT = 1350

    private const val MARGIN = 88f
    private const val BORDER_INSET = 40f
    private const val BORDER_STROKE = 3f

    private const val DATE_SIZE = 34f
    private const val DAY_SIZE = 64f
    private const val LIFT_NAME_SIZE = 32f
    private const val LIFT_VALUE_SIZE = 48f
    private const val OVERFLOW_SIZE = 30f
    private const val FOOTER_SIZE = 34f
    private const val WORDMARK_SIZE = 26f

    private const val DATE_Y = 170f
    private const val DAY_Y = 256f
    private const val LIFTS_START_Y = 386f
    private const val LIFT_ROW_PITCH = 100f
    private const val WORDMARK_Y = HEIGHT - 70f
    private const val FOOTER_Y = WORDMARK_Y - 60f

    /** Space reserved for a lift's value column, right-aligned — the name
     *  column ellipsizes rather than run into it. */
    private const val VALUE_COLUMN_WIDTH = 300f
    private const val NAME_VALUE_GAP = 24f

    /** Token-derived colors supplied to the renderer. Kept as a small test
     *  seam because Robolectric does not reliably rasterize text/strokes. */
    internal fun colorInput(dayIndex: Int): ColorInput = ColorInput(
        background = DarkBackground.toArgb(),
        accent = accentBright(dayIndex).toArgb(),
    )

    fun render(context: Context, content: ShareCardContent): Bitmap {
        val colors = colorInput(content.dayIndex)
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.background)

        val medium = font(context, R.font.barlow_condensed_medium, Typeface.NORMAL)
        val semibold = font(context, R.font.barlow_condensed_semibold, Typeface.NORMAL)
        val bold = font(context, R.font.barlow_condensed_bold, Typeface.BOLD)

        drawBorder(canvas)

        val left = MARGIN
        val right = WIDTH - MARGIN

        canvas.drawText(content.dateLine, left, DATE_Y, textPaint(medium, DATE_SIZE, DarkTextSecondary.toArgb()))
        // Exported share cards are designed artifacts pinned to DARK, independent
        // of the phone theme, so their authored bright foreground stays fixed.
        canvas.drawText(content.dayLine, left, DAY_Y, textPaint(bold, DAY_SIZE, colors.accent))

        val namePaint = textPaint(medium, LIFT_NAME_SIZE, DarkTextSecondary.toArgb())
        val valuePaint = textPaint(bold, LIFT_VALUE_SIZE, DarkTextPrimary.toArgb()).apply { textAlign = Paint.Align.RIGHT }
        val nameMaxWidth = (right - left) - VALUE_COLUMN_WIDTH - NAME_VALUE_GAP

        content.liftLines.forEachIndexed { i, lift ->
            val y = LIFTS_START_Y + i * LIFT_ROW_PITCH
            val name = TextUtils.ellipsize(lift.name, namePaint, nameMaxWidth, TextUtils.TruncateAt.END).toString()
            canvas.drawText(name, left, y, namePaint)
            canvas.drawText(lift.value, right, y, valuePaint)
        }

        content.overflowLine?.let { overflow ->
            val y = LIFTS_START_Y + content.liftLines.size * LIFT_ROW_PITCH
            canvas.drawText(overflow, left, y, textPaint(medium, OVERFLOW_SIZE, DarkTextFaint.toArgb()))
        }

        canvas.drawText(content.footerLine, left, FOOTER_Y, textPaint(semibold, FOOTER_SIZE, DarkTextSecondary.toArgb()))
        canvas.drawText("strength.log", left, WORDMARK_Y, textPaint(medium, WORDMARK_SIZE, DarkTextFaint.toArgb()))

        return bitmap
    }

    private fun drawBorder(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DarkBorder.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = BORDER_STROKE
        }
        canvas.drawRect(BORDER_INSET, BORDER_INSET, WIDTH - BORDER_INSET, HEIGHT - BORDER_INSET, paint)
    }

    /** Every drawn string is already in its intended case — dates/day/footer
     *  are pre-uppercased in [ShareCardContentBuilder] — so this only sets a
     *  face/size/color; the tracked-caps letter-spacing is the one shared
     *  touch that makes it read like the rest of the app's caps labels.
     *  [TextPaint] (not plain [Paint]) so a lift-name line can be measured and
     *  ellipsized by [TextUtils.ellipsize]. */
    private fun textPaint(typeface: Typeface, size: Float, argb: Int): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = size
        color = argb
        letterSpacing = 0.02f
    }

    /** [ResourcesCompat.getFont] can return null if the font resource fails to
     *  load; a plain bold/normal system fallback keeps rendering going rather
     *  than crashing the share tap over a missing glyph face. */
    private fun font(context: Context, resId: Int, fallbackStyle: Int): Typeface =
        ResourcesCompat.getFont(context, resId) ?: Typeface.defaultFromStyle(fallbackStyle)
}
